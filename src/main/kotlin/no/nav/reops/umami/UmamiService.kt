package no.nav.reops.umami

import no.nav.reops.event.Event
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

@Service
class UmamiService(
    private val umamiClient: WebClient
) {

    private val logger = LoggerFactory.getLogger(UmamiService::class.java)

    fun sendEvent(event: Event, userAgent: String) {
        umamiClient.post()
            .uri("/api/send")
            .contentType(MediaType.APPLICATION_JSON)
            .header("User-Agent", userAgent)
            .bodyValue(event)
            .retrieve()
            .bodyToMono<String>()
            .doOnNext { resp ->
                logger.info("Umami response: {}", resp)
            }
            .onErrorResume { ex ->
                logger.error("Failed to send event to Umami", ex)
                if (ex is org.springframework.web.reactive.function.client.WebClientResponseException) {
                    logger.warn("Umami error body: {}", ex.responseBodyAsString)
                }
                Mono.empty()
            }
            .subscribe()
    }
}