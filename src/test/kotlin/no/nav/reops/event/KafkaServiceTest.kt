package no.nav.reops.event

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.reops.filter.FilterService
import no.nav.reops.umami.UmamiService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.util.*

class KafkaServiceTest {
    private lateinit var filterService: FilterService
    private lateinit var umamiService: UmamiService
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var kafkaService: KafkaService

    @BeforeEach
    fun setUp() {
        filterService = mock()
        umamiService = mock()
        meterRegistry = SimpleMeterRegistry()
        kafkaService = KafkaService(filterService, umamiService, meterRegistry)
    }

    @Test
    fun `eventListen - success increments success counter and calls services`() {
        val event = mock<Event>()
        val filteredEvent = mock<Event>()
        val key = "key"
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K)"
        val excludeFilters = "a, b,,  c  "

        whenever(filterService.filterEvent(eq(event), eq(setOf("a", "b", "c")))).thenReturn(filteredEvent)

        val record = consumerRecord(key = key, value = event)
        kafkaService.eventListen(
            event = event, key = key, userAgent = userAgent, excludeFilters = excludeFilters, record = record
        )

        verify(filterService).filterEvent(eq(event), eq(setOf("a", "b", "c")))
        verify(umamiService).sendEvent(eq(filteredEvent), eq(userAgent))

        val success = meterRegistry.find("kafka_events_processed_total").tag("result", "success").counter()
        val failure = meterRegistry.find("kafka_events_processed_total").tag("result", "failure").counter()

        assertThat(success?.count()).isEqualTo(1.0)
        assertThat(failure?.count()).isEqualTo(0.0)
    }

    @Test
    fun `eventListen - null excludeFilters passes empty set`() {
        val event = mock<Event>()
        val filteredEvent = mock<Event>()
        val key = "key"
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K)"

        whenever(filterService.filterEvent(eq(event), eq(emptySet()))).thenReturn(filteredEvent)

        val record = consumerRecord(key = key, value = event)

        kafkaService.eventListen(
            event = event, key = key, userAgent = userAgent, excludeFilters = null, record = record
        )

        verify(filterService).filterEvent(eq(event), eq(emptySet()))
        verify(umamiService).sendEvent(eq(filteredEvent), eq(userAgent))

        val success = meterRegistry.find("kafka_events_processed_total").tag("result", "success").counter()
        val failure = meterRegistry.find("kafka_events_processed_total").tag("result", "failure").counter()

        assertThat(success?.count()).isEqualTo(1.0)
        assertThat(failure?.count()).isEqualTo(0.0)
    }

    @Test
    fun `eventListen - exception increments failure counter and rethrows`() {
        val event = mock<Event>()
        val key = "key"
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K)"

        whenever(filterService.filterEvent(eq(event), any())).thenThrow(RuntimeException("boom"))

        val record = consumerRecord(key = key, value = event)

        assertThatThrownBy {
            kafkaService.eventListen(
                event = event, key = key, userAgent = userAgent, excludeFilters = "", record = record
            )
        }.isInstanceOf(RuntimeException::class.java)

        verify(filterService).filterEvent(eq(event), eq(emptySet()))
        verify(umamiService, never()).sendEvent(any(), any())

        val success = meterRegistry.find("kafka_events_processed_total").tag("result", "success").counter()
        val failure = meterRegistry.find("kafka_events_processed_total").tag("result", "failure").counter()

        assertThat(success?.count()).isEqualTo(0.0)
        assertThat(failure?.count()).isEqualTo(1.0)
    }

    private fun consumerRecord(key: String, value: Event): ConsumerRecord<String, Event> = ConsumerRecord(
        "topic", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, key, value, RecordHeaders(), Optional.empty()
    )

}
