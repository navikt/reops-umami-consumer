package no.nav.reops.event

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.filter.FilterService
import no.nav.reops.umami.UmamiService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.annotation.BackOff
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Service

const val USER_AGENT = "user-agent"
const val OPT_OUT_FILTERS = "x-opt-out-filters"
const val FORWARDED_FOR = "x-forwarded-for"

@Service
class KafkaService(
    private val filterService: FilterService, private val umamiService: UmamiService, meterRegistry: MeterRegistry
) {
    private val kafkaEventsSuccess: Counter =
        Counter.builder("kafka_events_processed_total").tag("result", "success").register(meterRegistry)

    private val kafkaEventsFailure: Counter =
        Counter.builder("kafka_events_processed_total").tag("result", "failure").register(meterRegistry)

    private val kafkaEventsDlt: Counter =
        Counter.builder("kafka_events_dlt_total").register(meterRegistry)

    @RetryableTopic(
        attempts = "2", // Dont change! - creates more topics
        backOff = BackOff(delayString = "\${spring.kafka.retry.backoff-delay:300000}"), // Dont add more backoff! - creates more topics
        sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
        autoCreateTopics = "false",
        concurrency = "1"
    )
    @KafkaListener(
        topics = ["\${spring.kafka.topic}"],
        groupId = "\${spring.kafka.consumer.group-id}",
        concurrency = "\${spring.kafka.listener.concurrency}",
    )
    fun eventListen(
        event: Event,
        @Header(KafkaHeaders.RECEIVED_KEY) key: String,
        @Header(name = USER_AGENT, required = false) userAgent: String?,
        @Header(name = OPT_OUT_FILTERS, required = false) optOutFilters: String?,
        @Header(name = FORWARDED_FOR, required = false) forwardedFor: String?,
        record: ConsumerRecord<String, Event>
    ) {
        try {
            LOG.info("Received event with key={} website={} offset={} partition={}", key, event.payload.website, record.offset(), record.partition())
            val parsedOptOutFilters = OptOutFilter.parseHeader(optOutFilters)
            val filteredEvent = filterService.filterEvent(event, parsedOptOutFilters)

            val safeUserAgent = userAgent?.trim().takeUnless { it.isNullOrEmpty() } ?: "unknown"
            val safeOptOutFilters = optOutFilters?.trim().takeUnless { it.isNullOrEmpty() }
            val safeForwardedFor = forwardedFor?.trim().takeUnless { it.isNullOrEmpty() }
            val normalized = filteredEvent.normalizedForUmami()

            umamiService.sendEvent(normalized, safeUserAgent, safeOptOutFilters, safeForwardedFor)
            kafkaEventsSuccess.increment()
        } catch (ex: Exception) {
            kafkaEventsFailure.increment()
            LOG.error(
                "Failed processing kafka event key={} offset={} partition={}",
                key,
                record.offset(),
                record.partition(),
                ex
            )
            throw ex
        }
    }

    @DltHandler
    fun handleDlt(record: ConsumerRecord<String, Event>) {
        kafkaEventsDlt.increment()
        val key = record.key()
        val website = runCatching { record.value()?.payload?.website }.getOrNull()
        LOG.error(
            "Message exhausted all retries and sent to DLT: key={} website={} offset={} partition={} topic={}",
            key, website, record.offset(), record.partition(), record.topic()
        )
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(KafkaService::class.java)
    }
}

private fun Event.normalizedForUmami(): Event {
    val normalizedName = this.payload.name?.trim().takeUnless { it.isNullOrEmpty() }
    return this.copy(payload = this.payload.copy(name = normalizedName))
}