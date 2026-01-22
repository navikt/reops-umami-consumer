package no.nav.reops.event

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer
import org.springframework.kafka.support.serializer.JacksonJsonSerializer

@EnableKafka
@Configuration
class KafkaConfiguration(
    private val kafkaProperties: KafkaProperties
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
        factory.setConsumerFactory(consumerFactory)
        return factory
    }
}