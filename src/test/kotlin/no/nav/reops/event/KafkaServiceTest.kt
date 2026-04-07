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
import java.util.Optional
import java.util.UUID

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
        val event = sampleEvent(name = null)
        val filteredEvent = sampleEvent(name = "besøk")

        val key = "key"
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K)"
        val forwardedFor = "127.0.0.1"

        whenever(filterService.filterEvent(eq(event), isNull())).thenReturn(filteredEvent)

        val record = consumerRecord(key = key, value = event)

        kafkaService.eventListen(
            event = event,
            key = key,
            userAgent = userAgent,
            optOutFilters = null,
            forwardedFor = forwardedFor,
            record = record
        )

        verify(filterService).filterEvent(eq(event), isNull())

        verify(umamiService).sendEvent(
            check { sent ->
                assertThat(sent.type).isEqualTo("event")
                assertThat(sent.payload.website).isEqualTo(filteredEvent.payload.website)
                assertThat(sent.payload.name).isEqualTo("besøk")
            },
            eq(userAgent),
            isNull(),
            eq(forwardedFor)
        )

        val success = meterRegistry.find("kafka_events_processed_total").tag("result", "success").counter()
        val failure = meterRegistry.find("kafka_events_processed_total").tag("result", "failure").counter()

        assertThat(success?.count()).isEqualTo(1.0)
        assertThat(failure?.count()).isEqualTo(0.0)

        val eventTypeCounter = meterRegistry.find("kafka_events_by_type_total").tag("type", "event").counter()
        assertThat(eventTypeCounter?.count()).isEqualTo(1.0)
    }

    @Test
    fun `eventListen - identify type increments identify counter`() {
        val event = sampleEvent(name = null, type = "identify")
        val filteredEvent = sampleEvent(name = null, type = "identify")

        val key = "key"
        val userAgent = "Mozilla/5.0"
        val forwardedFor = "127.0.0.1"

        whenever(filterService.filterEvent(eq(event), isNull())).thenReturn(filteredEvent)

        val record = consumerRecord(key = key, value = event)

        kafkaService.eventListen(
            event = event,
            key = key,
            userAgent = userAgent,
            optOutFilters = null,
            forwardedFor = forwardedFor,
            record = record
        )

        val identifyCounter = meterRegistry.find("kafka_events_by_type_total").tag("type", "identify").counter()
        assertThat(identifyCounter?.count()).isEqualTo(1.0)

        val eventCounter = meterRegistry.find("kafka_events_by_type_total").tag("type", "event").counter()
        assertThat(eventCounter?.count()).isEqualTo(0.0)
    }

    @Test
    fun `eventListen - unknown type increments other counter`() {
        val event = sampleEvent(name = null, type = "unknown_type")
        val filteredEvent = sampleEvent(name = null, type = "unknown_type")

        val key = "key"
        val userAgent = "Mozilla/5.0"
        val forwardedFor = "127.0.0.1"

        whenever(filterService.filterEvent(eq(event), isNull())).thenReturn(filteredEvent)

        val record = consumerRecord(key = key, value = event)

        kafkaService.eventListen(
            event = event,
            key = key,
            userAgent = userAgent,
            optOutFilters = null,
            forwardedFor = forwardedFor,
            record = record
        )

        val otherCounter = meterRegistry.find("kafka_events_by_type_total").tag("type", "unknown").counter()
        assertThat(otherCounter?.count()).isEqualTo(1.0)
    }

    @Test
    fun `eventListen - success with defaults`() {
        val event = sampleEvent(name = null)
        val filteredEvent = sampleEvent(name = null)

        val key = "key"
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K)"
        val forwardedFor = "127.0.0.1"

        whenever(filterService.filterEvent(eq(event), isNull())).thenReturn(filteredEvent)

        val record = consumerRecord(key = key, value = event)

        kafkaService.eventListen(
            event = event,
            key = key,
            userAgent = userAgent,
            optOutFilters = null,
            forwardedFor = forwardedFor,
            record = record
        )

        verify(filterService).filterEvent(eq(event), isNull())

        verify(umamiService).sendEvent(
            check { sent ->
                assertThat(sent.type).isEqualTo("event")
                assertThat(sent.payload.website).isEqualTo(filteredEvent.payload.website)
                assertThat(sent.payload.name).isNull()
            },
            eq(userAgent),
            isNull(),
            eq(forwardedFor)
        )

        val success = meterRegistry.find("kafka_events_processed_total").tag("result", "success").counter()
        val failure = meterRegistry.find("kafka_events_processed_total").tag("result", "failure").counter()

        assertThat(success?.count()).isEqualTo(1.0)
        assertThat(failure?.count()).isEqualTo(0.0)
    }

    @Test
    fun `eventListen - exception increments failure counter and rethrows`() {
        val event = sampleEvent(name = null)

        val key = "key"
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K)"
        val forwardedFor = "127.0.0.1"

        whenever(filterService.filterEvent(eq(event), isNull())).thenThrow(RuntimeException("boom"))

        val record = consumerRecord(key = key, value = event)

        assertThatThrownBy {
            kafkaService.eventListen(
                event = event,
                key = key,
                userAgent = userAgent,
                optOutFilters = null,
                forwardedFor = forwardedFor,
                record = record
            )
        }.isInstanceOf(RuntimeException::class.java)

        verify(filterService).filterEvent(eq(event), isNull())
        verify(umamiService, never()).sendEvent(any(), any(), anyOrNull(), anyOrNull())

        val success = meterRegistry.find("kafka_events_processed_total").tag("result", "success").counter()
        val failure = meterRegistry.find("kafka_events_processed_total").tag("result", "failure").counter()

        assertThat(success?.count()).isEqualTo(0.0)
        assertThat(failure?.count()).isEqualTo(1.0)
    }

    private fun consumerRecord(key: String, value: Event): ConsumerRecord<String, Event> = ConsumerRecord(
        "topic", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, key, value, RecordHeaders(), Optional.empty()
    )

    private fun sampleEvent(name: String?, type: String = "event"): Event = Event(
        type = type, payload = Event.Payload(
            website = UUID.fromString("1dbfe4e9-bf8b-45d9-9305-928f22200bc0"),
            hostname = "felgen.intern.nav.no",
            screen = "172x111",
            language = "en-US",
            title = "Nav Webapps | Snarveier",
            url = "/path/to/thing",
            referrer = "",
            name = name
        )
    )
}