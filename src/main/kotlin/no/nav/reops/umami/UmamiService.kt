package no.nav.reops.umami

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import no.nav.reops.event.KafkaService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.Duration

@Service
class UmamiService(
    private val umamiClient: WebClient,
    meterRegistry: MeterRegistry
) {
    private val umamiRequestsSuccess: Counter =
        Counter.builder("umami_requests_total")
            .tag("result", "success")
            .register(meterRegistry)

    private val umamiRequestsFailure: Counter =
        Counter.builder("umami_requests_total")
            .tag("result", "failure")
            .register(meterRegistry)

    fun sendEvent(event: Event, userAgent: String) {
        try {
            umamiClient.post()
                .uri("/api/send")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", userAgent)
                .bodyValue(event)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { resp ->
                    resp.bodyToMono<String>()
                        .defaultIfEmpty("")
                        .flatMap { body ->
                            LOG.error("Umami responded with status=${resp.statusCode().value()} body=$body")
                            Mono.error(RuntimeException("Umami error ${resp.statusCode().value()}"))
                        }
                }
                .bodyToMono<String>()
                .defaultIfEmpty("")
                .doOnNext { body ->
                    LOG.info("Umami response body=$body")
                    umamiRequestsSuccess.increment()
                }
                .onErrorResume { ex ->
                    umamiRequestsFailure.increment()
                    LOG.error("Failed to send event to Umami", ex)
                    Mono.empty()
                }
                .block(Duration.ofSeconds(5))
        } catch (ex: Exception) {
            umamiRequestsFailure.increment()
            LOG.error("Failed to send event to Umami", ex)
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(UmamiService::class.java)
    }
}