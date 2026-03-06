package no.nav.reops.event

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.kafka.support.Acknowledgment
import org.springframework.messaging.Message
import org.springframework.kafka.support.SendResult
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DlqRetryServiceTest {
    private lateinit var kafkaTemplate: KafkaTemplate<String, Event>
    private lateinit var registry: KafkaListenerEndpointRegistry
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var dlqRetryService: DlqRetryService

    @BeforeEach
    fun setUp() {
        kafkaTemplate = mock()
        registry = mock()
        meterRegistry = SimpleMeterRegistry()
        dlqRetryService = DlqRetryService(kafkaTemplate, registry, "test-topic", meterRegistry)
    }

    @Test
    fun `startDlqRetry starts listener container when not running`() {
        val container: MessageListenerContainer = mock {
            on { isRunning } doReturn false
        }
        whenever(registry.getListenerContainer("dlq-retry-listener")).thenReturn(container)

        dlqRetryService.startDlqRetry()

        verify(container).start()
    }

    @Test
    fun `startDlqRetry does not start listener when already running`() {
        val container: MessageListenerContainer = mock {
            on { isRunning } doReturn true
        }
        whenever(registry.getListenerContainer("dlq-retry-listener")).thenReturn(container)

        dlqRetryService.startDlqRetry()

        verify(container, never()).start()
    }

    @Test
    fun `retryDlqBatch republishes records to main topic and acknowledges`() {
        val event = sampleEvent()
        val record = dlqConsumerRecord("key-1", event)
        val ack: Acknowledgment = mock()
        val container: MessageListenerContainer = mock {
            on { isRunning } doReturn true
        }
        whenever(registry.getListenerContainer("dlq-retry-listener")).thenReturn(container)
        whenever(kafkaTemplate.send(any<Message<Event>>())).thenReturn(CompletableFuture.completedFuture(null))

        dlqRetryService.retryDlqBatch(listOf(record), ack)

        verify(kafkaTemplate).send(argThat<Message<Event>> { payload == event })
        verify(ack).acknowledge()
        verify(container).stop()

        val success = meterRegistry.find("dlq_retry_total").tag("result", "success").counter()
        assertThat(success?.count()).isEqualTo(1.0)
    }

    @Test
    fun `retryDlqBatch republishes multiple records`() {
        val event1 = sampleEvent()
        val event2 = sampleEvent()
        val records = listOf(
            dlqConsumerRecord("key-1", event1),
            dlqConsumerRecord("key-2", event2)
        )
        val ack: Acknowledgment = mock()
        val container: MessageListenerContainer = mock {
            on { isRunning } doReturn true
        }
        whenever(registry.getListenerContainer("dlq-retry-listener")).thenReturn(container)
        whenever(kafkaTemplate.send(any<Message<Event>>())).thenReturn(CompletableFuture.completedFuture(null))

        dlqRetryService.retryDlqBatch(records, ack)

        verify(kafkaTemplate, times(2)).send(any<Message<Event>>())
        verify(ack).acknowledge()

        val success = meterRegistry.find("dlq_retry_total").tag("result", "success").counter()
        assertThat(success?.count()).isEqualTo(2.0)
    }

    @Test
    fun `retryDlqBatch increments failure counter on error and does not acknowledge`() {
        val event = sampleEvent()
        val record = dlqConsumerRecord("key-1", event)
        val ack: Acknowledgment = mock()
        val container: MessageListenerContainer = mock {
            on { isRunning } doReturn true
        }
        whenever(registry.getListenerContainer("dlq-retry-listener")).thenReturn(container)
        whenever(kafkaTemplate.send(any<Message<Event>>())).thenReturn(
            CompletableFuture<SendResult<String, Event>>().apply { completeExceptionally(RuntimeException("send failed")) }
        )

        dlqRetryService.retryDlqBatch(listOf(record), ack)

        verify(ack, never()).acknowledge()
        verify(container).stop()

        val failure = meterRegistry.find("dlq_retry_total").tag("result", "failure").counter()
        assertThat(failure?.count()).isEqualTo(1.0)
    }

    private fun dlqConsumerRecord(key: String, value: Event): ConsumerRecord<String, Event> = ConsumerRecord(
        "test-topic-dlq", 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, key, value, RecordHeaders(), Optional.empty()
    )

    private fun sampleEvent(): Event = Event(
        type = "event", payload = Event.Payload(
            website = UUID.fromString("1dbfe4e9-bf8b-45d9-9305-928f22200bc0"),
            hostname = "test.nav.no",
            url = "/test"
        )
    )
}

