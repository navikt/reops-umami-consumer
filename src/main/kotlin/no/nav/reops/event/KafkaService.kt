package no.nav.reops.event

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
    private val umamiService: UmamiService
){
    private val logger: Logger = LoggerFactory.getLogger(KafkaService::class.java)

    @KafkaListener(
        topics = ["\${spring.kafka.topic}"],
        groupId = "\${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun eventListen(
        event: Event,
        @Header(KafkaHeaders.RECEIVED_KEY) key: String,
        @Header(name = "User-Agent", required = true) userAgent: String,
        record: ConsumerRecord<String, Event>
    ) {
        logger.info("Received event with key: $key at offset: ${record.offset()} in partition: ${record.partition()}")
        val filteredEvent = filterService.filterEvent(event)
        umamiService.sendEvent(filteredEvent, userAgent)
    }
}