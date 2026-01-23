package no.nav.reops.event

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.filter.FilterService
import no.nav.reops.umami.UmamiService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Service

const val USER_AGENT = "user-agent"
const val EXCLUDE_FILTERS = "x-exclude-filters"
const val FORWARDED_FOR = "x-forwarded-for"

@Service
class KafkaService(
    private val filterService: FilterService, private val umamiService: UmamiService, meterRegistry: MeterRegistry
) {
    private val kafkaEventsSuccess: Counter =
        Counter.builder("kafka_events_processed_total").tag("result", "success").register(meterRegistry)

    private val kafkaEventsFailure: Counter =
        Counter.builder("kafka_events_processed_total").tag("result", "failure").register(meterRegistry)

    @KafkaListener(
        topics = ["\${spring.kafka.topic}"],
        groupId = "\${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun eventListen(
        event: Event,
        @Header(KafkaHeaders.RECEIVED_KEY) key: String,
        @Header(name = USER_AGENT, required = false) userAgent: String?,
        @Header(name = EXCLUDE_FILTERS, required = false) excludeFilters: String?,
        @Header(name = FORWARDED_FOR, required = false) forwardedFor: String?,
        record: ConsumerRecord<String, Event>
    ) {
        LOG.info("Received event with key={} offset={} partition={}", key, record.offset(), record.partition())
        val excludeKeys = ExcludeFiltersParser.parse(excludeFilters)
        LOG.info("Exclude filters for this event: {}", excludeKeys)

        try {
            val filteredEvent = filterService.filterEvent(event, excludeKeys)
            val safeUserAgent = userAgent?.trim().takeUnless { it.isNullOrEmpty() } ?: "unknown"
            val safeForwardedFor = forwardedFor?.trim().takeUnless { it.isNullOrEmpty() }

            umamiService.sendEvent(filteredEvent, safeUserAgent, safeForwardedFor)
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

    private companion object {
        private val LOG = LoggerFactory.getLogger(KafkaService::class.java)
    }
}

internal object ExcludeFiltersParser {
    fun parse(headerValue: String?): Set<String> =
        headerValue?.split(',')?.asSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
}
