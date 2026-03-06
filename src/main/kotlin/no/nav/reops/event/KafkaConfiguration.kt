package no.nav.reops.event

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer
import org.springframework.kafka.support.serializer.JacksonJsonSerializer
import org.springframework.util.backoff.FixedBackOff

@EnableKafka
@Configuration
class KafkaConfiguration(
    private val kafkaProperties: KafkaProperties,
    @Value("\${spring.kafka.dlq-topic}") private val dlqTopic: String
) {

    @Bean
    fun consumerFactory(): ConsumerFactory<String, Event> {
        val configProps = kafkaProperties.buildConsumerProperties().toMutableMap()
        configProps[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        configProps[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = JacksonJsonDeserializer::class.java
        configProps.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

        val valueDeserializer = JacksonJsonDeserializer(Event::class.java).apply {
            addTrustedPackages("*")
        }

        return DefaultKafkaConsumerFactory(
            configProps, StringDeserializer(), valueDeserializer
        )
    }

    @Bean
    fun dlqConsumerFactory(): ConsumerFactory<String, Event> {
        val configProps = kafkaProperties.buildConsumerProperties().toMutableMap()
        configProps[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        configProps[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = JacksonJsonDeserializer::class.java
        configProps[ConsumerConfig.GROUP_ID_CONFIG] = "${kafkaProperties.consumer.groupId}"
        configProps[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        configProps[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = 10

        val valueDeserializer = JacksonJsonDeserializer(Event::class.java).apply {
            addTrustedPackages("*")
        }

        return DefaultKafkaConsumerFactory(
            configProps, StringDeserializer(), valueDeserializer
        )
    }

    @Bean
    fun mainTopicKafkaTemplate(): KafkaTemplate<String, Event> {
        val producerProps = kafkaProperties.buildProducerProperties().toMutableMap()
        producerProps[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        producerProps.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)
        return KafkaTemplate(
            DefaultKafkaProducerFactory(
                producerProps,
                StringSerializer(),
                JacksonJsonSerializer<Event>()
            )
        )
    }

    @Bean
    fun kafkaErrorHandler(): DefaultErrorHandler {
        val producerProps = kafkaProperties.buildProducerProperties().toMutableMap()
        producerProps[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        producerProps.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)
        val dlqTemplate = KafkaTemplate(
            DefaultKafkaProducerFactory(
                producerProps,
                StringSerializer(),
                JacksonJsonSerializer<Any>()
            )
        )

        val recoverer = DeadLetterPublishingRecoverer(dlqTemplate) { record: ConsumerRecord<*, *>, ex: Exception ->
            LOG.warn(
                "Sending failed record to DLQ topic={} key={} offset={} partition={}",
                dlqTopic, record.key(), record.offset(), record.partition(), ex
            )
            TopicPartition(dlqTopic, -1)
        }
        return DefaultErrorHandler(recoverer, FixedBackOff(0L, 0L))
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(KafkaConfiguration::class.java)
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, Event>,
        kafkaErrorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, Event> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, Event>()
        factory.setConsumerFactory(consumerFactory)
        factory.containerProperties.ackMode = org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL
        factory.setCommonErrorHandler(kafkaErrorHandler)
        return factory
    }

    @Bean
    fun dlqKafkaListenerContainerFactory(
        dlqConsumerFactory: ConsumerFactory<String, Event>
    ): ConcurrentKafkaListenerContainerFactory<String, Event> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, Event>()
        factory.setConsumerFactory(dlqConsumerFactory)
        factory.containerProperties.ackMode = org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL
        return factory
    }
}