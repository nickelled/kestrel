package com.dreweaster.ddd.kestrel.infrastructure.http.eventsource.consumer

import com.dreweaster.ddd.kestrel.application.*
import com.dreweaster.ddd.kestrel.application.scheduling.Job
import com.dreweaster.ddd.kestrel.application.scheduling.Scheduler
import com.dreweaster.ddd.kestrel.domain.DomainEvent
import com.dreweaster.ddd.kestrel.domain.DomainEventTag
import com.dreweaster.ddd.kestrel.infrastructure.http.eventsource.HttpJsonEventQuery
import com.dreweaster.ddd.kestrel.infrastructure.http.eventsource.consumer.offset.EventStreamOffset
import com.dreweaster.ddd.kestrel.infrastructure.http.eventsource.consumer.offset.LastProcessedOffset
import com.dreweaster.ddd.kestrel.infrastructure.http.eventsource.consumer.offset.OffsetTracker
import com.dreweaster.ddd.kestrel.infrastructure.http.eventsource.consumer.reporting.BoundedContextHttpEventSourceReporter
import com.github.salomonbrys.kotson.long
import com.github.salomonbrys.kotson.nullString
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.switchIfEmpty
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

typealias FullyQualifiedClassName = String

data class HttpJsonEventMapper<T: DomainEvent>(
    val targetEventClass: KClass<T>,
    val sourceEventTag: DomainEventTag,
    val sourceEventType: FullyQualifiedClassName,
    val map: (JsonObject) -> T)

data class FeedEvent(val json: JsonObject) {
    val payload: JsonObject by lazy { json["payload"].asJsonObject }
    val type: String by lazy { json["type"].string }
    val offset: Long by lazy { json["offset"].long }
    val metadata: EventMetadata by lazy {
        EventMetadata(
            EventId(json["id"].string),
            AggregateId(json["aggregate_id"].string),
            CausationId(json["causation_id"].string),
            json["correlation_id"].nullString?.let { CorrelationId(it) },
            json["sequence_number"].long
        )
    }
}

data class EventFeedPage(val json: JsonObject, val pageSize: Int) {
    val events: List<FeedEvent> by lazy { json["events"].asJsonArray.toList().map { FeedEvent(it.asJsonObject) }}
    val queryMaxOffset: Long by lazy { json["query_max_offset"].long }
    val globalMaxOffset: Long by lazy { json["global_max_offset"].long }
    val pageStartOffset: Long by lazy { json["page_start_offset"].long }
    val pageEndOffset: Long by lazy { json["page_end_offset"].long }
}

interface BoundedContextHttpEventSourceConfiguration {

    val producerEndpointProtocol: String

    val producerEndpointHostname: String

    val producerEndpointPort: Int

    val producerEndpointPath: String

    fun batchSizeFor(subscriptionName: String): Int

    fun repeatScheduleFor(subscriptionName: String): Duration

    fun timeoutFor(subscriptionName: String): Duration

    fun enabled(subscriptionName: String): Boolean
}

// TODO: Need to factor skipped events into batch size - i.e. always event minimum of batch size even if that means fetching multiple batches
// TODO: Renable monitoring
class BoundedContextHttpEventSource(
        val name: BoundedContextName,
        val httpClient: HttpClient,
        val configuration: BoundedContextHttpEventSourceConfiguration,
        eventMappers: List<HttpJsonEventMapper<*>>,
        val offsetManager: OffsetTracker,
        private val jobManager: Scheduler): BoundedContextEventSource {

    private val LOG = LoggerFactory.getLogger(BoundedContextHttpEventSource::class.java)

    private val targetClassToEventTag: Map<KClass<out DomainEvent>, DomainEventTag> = eventMappers.map { it.targetEventClass to it.sourceEventTag }.toMap()

    private val sourceEventTypeToMapper: Map<FullyQualifiedClassName, (JsonObject) -> DomainEvent> = eventMappers.map { it.sourceEventType to { jsonObject: JsonObject -> it.map(jsonObject)} }.toMap()

    private var reporters: List<BoundedContextHttpEventSourceReporter> = emptyList()

    fun addReporter(reporter: BoundedContextHttpEventSourceReporter): BoundedContextHttpEventSource {
        reporters += reporter
        return this
    }

    fun removeReporter(reporter: BoundedContextHttpEventSourceReporter): BoundedContextHttpEventSource {
        reporters -= reporter
        return this
    }

    override fun subscribe(handlers: Map<KClass<out DomainEvent>, ((DomainEvent, EventMetadata) -> Mono<Void>)>, subscriberConfiguration: BoundedContextSubscriberConfiguration) {
        val allTags = handlers.keys.map { targetClassToEventTag[it] ?: throw IllegalArgumentException("Unsupported event type: ${it.qualifiedName}") }.toSet()

        val job = ConsumeHttpEventStreamJob(
                tags = allTags,
                subscriberConfiguration = subscriberConfiguration,
                eventHandlers = handlers)

        if(configuration.enabled(subscriberConfiguration.name)) {
            jobManager.scheduleManyTimes(
                    repeatSchedule = configuration.repeatScheduleFor(subscriberConfiguration.name),
                    timeout = configuration.timeoutFor(subscriberConfiguration.name),
                    job = job)
        } else {
            LOG.warn("The event stream subscriber '${subscriberConfiguration.name}' is disabled")
        }
    }

    inner class ConsumeHttpEventStreamJob(
            private val eventHandlers: Map<KClass<out DomainEvent>, ((DomainEvent, EventMetadata) -> Mono<Void>)>,
            tags : Set<DomainEventTag>,
            private val subscriberConfiguration: BoundedContextSubscriberConfiguration) : Job {

        override val name = "${this@BoundedContextHttpEventSource.name.name}_${subscriberConfiguration.name}"

        private val requestFactory = HttpEventStreamSubscriptionEdenPolicy.from(subscriberConfiguration.edenPolicy)
            .newRequestFactory(
                subscriberConfiguration = configuration,
                tags = tags,
                batchSize = configuration.batchSizeFor(subscriberConfiguration.name))

        override fun execute(): Mono<Boolean> {
            return fetchOffset()
                .flatMap(fetchEventFeed)
                .flatMap { eventFeedPage ->
                    // TODO: Inefficient in that it updates offset after handling each event rather than in batches. Does reduce volume of redeliveries, though
                    processEvents(eventFeedPage)
                        .flatMap(saveOffset)
                        .then(Mono.just(hasBacklog(eventFeedPage)))
                }
        }

        private val handleEvent: (FeedEvent) -> Mono<Long> = { feedEvent ->
            (sourceEventTypeToMapper[feedEvent.type]?.let { mapper ->
                val event = mapper(feedEvent.payload)
                val eventHandler = eventHandlers[event::class]
                eventHandler?.invoke(event, feedEvent.metadata)
            } ?: Mono.empty()).then(Mono.just(feedEvent.offset))
        }

        private val saveOffset: (Long) -> Mono<Void> = { offset ->
            offsetManager.saveOffset(name, offset)
        }

        private fun fetchOffset(): Mono<out EventStreamOffset> = offsetManager.getOffset(name)

        private val fetchEventFeed: (EventStreamOffset) -> Mono<EventFeedPage> = { eventStreamOffset ->
            val offset = when(eventStreamOffset) {
                is LastProcessedOffset -> eventStreamOffset.value
                else -> null
            }

            requestFactory.createRequest(offset)(httpClient).map {
                EventFeedPage(it, configuration.batchSizeFor(subscriberConfiguration.name))
            }
        }

        private fun processEvents(page: EventFeedPage): Flux<Long> {
            return if(page.events.isEmpty()) {
                val derivedOffset = maxOf(page.queryMaxOffset, page.globalMaxOffset)
                if(configuration.batchSizeFor(subscriberConfiguration.name) > 0 && derivedOffset > -1L) Flux.just(derivedOffset) else Flux.empty()
            } else {
                Flux.fromIterable(page.events).flatMap(handleEvent)
            }
        }

        private fun hasBacklog(page: EventFeedPage): Boolean {
            return page.pageSize > 0 && page.events.isNotEmpty() && (page.pageEndOffset < page.queryMaxOffset)
        }
    }
}

sealed class HttpEventStreamSubscriptionEdenPolicy {

    val jsonParser = JsonParser()

    companion object {
        fun from(policy: BoundedContextSubscriptionEdenPolicy) = when(policy) {
            BoundedContextSubscriptionEdenPolicy.FROM_NOW -> FromNow
            BoundedContextSubscriptionEdenPolicy.BEGINNING_OF_TIME -> BeginningOfTime
        }
    }

    abstract fun newRequestFactory(
            subscriberConfiguration: BoundedContextHttpEventSourceConfiguration,
            tags: Set<DomainEventTag>,
            batchSize: Int): RequestFactory

    interface RequestFactory {
        fun createRequest(lastProcessedOffset: Long?): (HttpClient) -> Mono<JsonObject>
    }
}

object BeginningOfTime : HttpEventStreamSubscriptionEdenPolicy() {
    override fun newRequestFactory(
            subscriberConfiguration: BoundedContextHttpEventSourceConfiguration,
            tags: Set<DomainEventTag>,
            batchSize: Int): RequestFactory {

        return object : RequestFactory {
            override fun createRequest(lastProcessedOffset: Long?): (HttpClient) -> Mono<JsonObject> {
                val query = HttpJsonEventQuery(
                    tags = tags,
                    afterOffset = lastProcessedOffset ?: -1L,
                    batchSize = batchSize
                )

                val url = query.eventsUrlFor(
                    protocol = subscriberConfiguration.producerEndpointProtocol,
                    hostname = subscriberConfiguration.producerEndpointHostname,
                    port = subscriberConfiguration.producerEndpointPort,
                    path = subscriberConfiguration.producerEndpointPath
                )

                return { client: HttpClient ->
                    client.get().uri(url.toString())
                        .responseContent()
                        .aggregate()
                        .asString()
                        .map { jsonParser.parse(it).asJsonObject }
                        .switchIfEmpty(Mono.error(RuntimeException("Error fetching events")))
                }
            }
        }
    }
}

object FromNow : HttpEventStreamSubscriptionEdenPolicy() {
    override fun newRequestFactory(
            subscriberConfiguration: BoundedContextHttpEventSourceConfiguration,
            tags: Set<DomainEventTag>,
            batchSize: Int): RequestFactory {

        val now = Instant.now() // cache now() once so doesn't refresh on every request

        return object : RequestFactory {
            override fun createRequest(lastProcessedOffset: Long?): (HttpClient) -> Mono<JsonObject> {
                if(lastProcessedOffset != null) {
                    val query = HttpJsonEventQuery(
                        tags = tags,
                        afterOffset = lastProcessedOffset,
                        batchSize = batchSize
                    )

                    val url = query.eventsUrlFor(
                        protocol = subscriberConfiguration.producerEndpointProtocol,
                        hostname = subscriberConfiguration.producerEndpointHostname,
                        port = subscriberConfiguration.producerEndpointPort,
                        path = subscriberConfiguration.producerEndpointPath
                    )

                    return { client: HttpClient ->
                        client.get().uri(url.toString())
                            .responseContent()
                            .aggregate()
                            .asString()
                            .map { jsonParser.parse(it).asJsonObject }
                            .switchIfEmpty(Mono.error(RuntimeException("Error fetching events")))
                    }
                } else {
                    val query = HttpJsonEventQuery(
                        tags = tags,
                        afterTimestamp = now,
                        batchSize = batchSize
                    )

                    val url = query.eventsUrlFor(
                        protocol = subscriberConfiguration.producerEndpointProtocol,
                        hostname = subscriberConfiguration.producerEndpointHostname,
                        port = subscriberConfiguration.producerEndpointPort,
                        path = subscriberConfiguration.producerEndpointPath
                    )

                    return { client: HttpClient ->
                        client.get().uri(url.toString())
                            .responseContent()
                            .aggregate()
                            .asString()
                            .map { jsonParser.parse(it).asJsonObject }
                            .switchIfEmpty(Mono.error(RuntimeException("Error fetching events")))
                    }
                }
            }
        }
    }
}