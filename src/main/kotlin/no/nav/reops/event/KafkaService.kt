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
        @Header(name = "User-Agent", required = true) userAgent: String,
        @Header(name = "X-Exclude-Filters", required = false) excludeFilters: String?,
        record: ConsumerRecord<String, Event>
    ) {
        LOG.info("Received event with key: $key at offset: ${record.offset()} in partition: ${record.partition()}")

        val excludeKeys: Set<String> =
            excludeFilters?.split(",")?.asSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet()

        LOG.info("Exclude filters for this event: $excludeKeys")

        try {
            val filteredEvent = filterService.filterEvent(event, excludeKeys)
            umamiService.sendEvent(filteredEvent, userAgent)
            kafkaEventsSuccess.increment()
        } catch (ex: Exception) {
            kafkaEventsFailure.increment()
            LOG.error("Failed processing kafka event key=$key offset=${record.offset()} partition=${record.partition()}", ex)
            throw ex
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(KafkaService::class.java)
    }
}