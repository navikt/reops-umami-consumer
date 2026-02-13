package no.nav.reops.event

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.filter.FilterService
import no.nav.reops.umami.UmamiService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

const val USER_AGENT = "user-agent"
const val EXCLUDE_FILTERS = "x-exclude-filters"
const val FORWARDED_FOR = "x-forwarded-for"

@Service
class KafkaService(
    private val filterService: FilterService, private val umamiService: UmamiService, private val meterRegistry: MeterRegistry
) {
    private val counters = ConcurrentHashMap<String, Counter>()

    @KafkaListener(
        topics = ["\${spring.kafka.topic}"],
        groupId = "\${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun eventListen(
        event: Event, ack: Acknowledgment,
        @Header(KafkaHeaders.RECEIVED_KEY) key: String,
        @Header(name = USER_AGENT, required = false) userAgent: String?,
        @Header(name = EXCLUDE_FILTERS, required = false) excludeFilters: String?,
        @Header(name = FORWARDED_FOR, required = false) forwardedFor: String?,
        record: ConsumerRecord<String, Event>
    ) {
        val websiteId = event.payload.website.toString()
        val successCounter = counter("kafka_events_processed_total", "success", websiteId)
        val failureCounter = counter("kafka_events_processed_total", "failure", websiteId)

        MDC.put("websiteId", websiteId)
        try {
            LOG.info("Received event with key={} websiteID={} offset={} partition={}", key, websiteId, record.offset(), record.partition())
            val filteredEvent = filterService.filterEvent(event, excludeFilters)

            val safeUserAgent = userAgent?.trim().takeUnless { it.isNullOrEmpty() } ?: "unknown"
            val safeForwardedFor = forwardedFor?.trim().takeUnless { it.isNullOrEmpty() }
            val normalized = filteredEvent.normalizedForUmami()

            umamiService.sendEvent(normalized, safeUserAgent, safeForwardedFor)
            successCounter.increment()
            ack.acknowledge()
        } catch (ex: Exception) {
            failureCounter.increment()
            LOG.error(
                "Failed processing kafka event key={} offset={} partition={}",
                key,
                record.offset(),
                record.partition(),
                ex
            )
            throw ex
        } finally {
            MDC.remove("websiteId")
        }
    }

    private fun counter(name: String, result: String, websiteId: String): Counter {
        val key = "result=$result|websiteId=$websiteId"
        return counters.computeIfAbsent(key) {
            meterRegistry.counter(name, "result", result, "websiteId", websiteId)
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(KafkaService::class.java)
    }
}

private fun Event.normalizedForUmami(): Event {
    val normalizedName = this.payload.name?.trim().takeUnless { it.isNullOrEmpty() }
    return this.copy(
        type = "event", payload = this.payload.copy(name = normalizedName)
    )
}