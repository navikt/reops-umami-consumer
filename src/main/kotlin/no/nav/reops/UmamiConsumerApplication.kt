package no.nav.reops

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class UmamiConsumerApplication

fun main(args: Array<String>) {
    runApplication<UmamiConsumerApplication>(*args)
}