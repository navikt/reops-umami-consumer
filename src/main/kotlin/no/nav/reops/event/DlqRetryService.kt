package no.nav.reops.event

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.support.MessageBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class DlqRetryService(
    private val mainTopicKafkaTemplate: KafkaTemplate<String, Event>,
    private val registry: KafkaListenerEndpointRegistry,
    @Value("\${spring.kafka.topic}") private val mainTopic: String,
    meterRegistry: MeterRegistry
) {
    private val dlqRetrySuccess: Counter =
        Counter.builder("dlq_retry_total").tag("result", "success").register(meterRegistry)

    private val dlqRetryFailure: Counter =
        Counter.builder("dlq_retry_total").tag("result", "failure").register(meterRegistry)

    @Scheduled(fixedDelayString = "\${reops.dlq.retry-interval:PT10M}")
    fun startDlqRetry() {
        LOG.info("Starting DLQ retry cycle")
        val container = registry.getListenerContainer(DLQ_LISTENER_ID)
        if (container != null && !container.isRunning) {
            container.start()
        }
    }

    @KafkaListener(
        id = DLQ_LISTENER_ID,
        groupId = "\${spring.kafka.consumer.group-id}",
        topics = ["\${spring.kafka.dlq-topic}"],
        containerFactory = "dlqKafkaListenerContainerFactory",
        autoStartup = "false",
        batch = "true"
    )
    fun retryDlqBatch(
        records: List<ConsumerRecord<String, Event>>,
        ack: Acknowledgment
    ) {
        LOG.info("DLQ retry: received {} records", records.size)
        try {
            for (record in records) {
                val key = record.key()
                val event = record.value()

                val headers = record.headers()
                    .filter { !it.key().startsWith("kafka_dlt-") }

                val messageBuilder = MessageBuilder.withPayload(event)
                    .setHeader(KafkaHeaders.TOPIC, mainTopic)
                    .setHeader(KafkaHeaders.KEY, key)

                for (header in headers) {
                    messageBuilder.setHeader(header.key(), String(header.value()))
                }

                mainTopicKafkaTemplate.send(messageBuilder.build()).get()

                LOG.info(
                    "DLQ retry: republished key={} offset={} partition={}",
                    key, record.offset(), record.partition()
                )
                dlqRetrySuccess.increment()
            }
            ack.acknowledge()
        } catch (ex: Exception) {
            dlqRetryFailure.increment(records.size.toDouble())
            LOG.error("DLQ retry: failed to republish batch", ex)
        } finally {
            stopDlqListener()
        }
    }

    private fun stopDlqListener() {
        val container = registry.getListenerContainer(DLQ_LISTENER_ID)
        if (container != null && container.isRunning) {
            LOG.info("DLQ retry: stopping listener after processing batch")
            container.stop()
        }
    }

    private companion object {
        private const val DLQ_LISTENER_ID = "dlq-retry-listener"
        private val LOG = LoggerFactory.getLogger(DlqRetryService::class.java)
    }
}

