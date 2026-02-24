package no.nav.reops.event

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer

@EnableKafka
@Configuration
class KafkaConfiguration(
    private val kafkaProperties: KafkaProperties,
    @Value("\${spring.kafka.listener.concurrency:1}") private val concurrency: Int
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
    fun kafkaListenerContainerFactory(consumerFactory: ConsumerFactory<String, Event>): ConcurrentKafkaListenerContainerFactory<String, Event> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, Event>()
        factory.consumerFactory = consumerFactory
        factory.setConcurrency(concurrency)
        factory.containerProperties.ackMode = org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL
        factory.containerProperties.authExceptionRetryInterval = java.time.Duration.ofSeconds(10)
        return factory
    }
}