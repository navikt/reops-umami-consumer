package no.nav.reops.event

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.filter.FilterService
import no.nav.reops.umami.UmamiService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Service

@Service
class KafkaService(
    private val filterService: FilterService,
    private val umamiService: UmamiService,
    meterRegistry: MeterRegistry
) {
    private val kafkaEventsSuccess: Counter =
        Counter.builder("kafka_events_processed_total")
            .tag("result", "success")
            .register(meterRegistry)

    private val kafkaEventsFailure: Counter =
        Counter.builder("kafka_events_processed_total")
            .tag("result", "failure")
            .register(meterRegistry)

    @KafkaListener(
        topics = ["\${spring.kafka.topic}"],
        groupId = "\${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun eventListen(
        event: Event,
        @Header(KafkaHeaders.RECEIVED_KEY) key: String,
        @Header(name = "User-Agent", required = true) userAgent: String,
        @Header(name = "X-Exclude-Filters", required = true) excludeFilters: String,
        record: ConsumerRecord<String, Event>
    ) {
        LOG.info("Received event with key: $key at offset: ${record.offset()} in partition: ${record.partition()}")
        val shouldExcludeFilters = excludeFilters.split(",").map { it.trim() }
        LOG.info("Exclude filters for this event: $shouldExcludeFilters")

        try {
            val filteredEvent = filterService.filterEvent(event)
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