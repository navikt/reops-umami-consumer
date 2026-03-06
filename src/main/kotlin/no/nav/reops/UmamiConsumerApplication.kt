package no.nav.reops

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class UmamiConsumerApplication

fun main(args: Array<String>) {
    runApplication<UmamiConsumerApplication>(*args)
}