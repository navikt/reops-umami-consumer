package no.nav.reops.umami

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import no.nav.reops.event.FORWARDED_FOR
import no.nav.reops.event.USER_AGENT
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.netty.http.client.PrematureCloseException
import reactor.util.retry.Retry
import java.time.Duration

@Service
class UmamiService(
    private val umamiClient: WebClient, meterRegistry: MeterRegistry
) {
    private val umamiRequestsSuccess: Counter = meterRegistry.counter("umami_requests_total", "result", "success")
    private val umamiRequestsFailure: Counter = meterRegistry.counter("umami_requests_total", "result", "failure")

    fun sendEvent(event: Event, userAgent: String, forwardedFor: String?) {
        umamiClient.post().uri("/api/send").contentType(MediaType.APPLICATION_JSON).header(USER_AGENT, userAgent)
            .apply { if (forwardedFor != null) header(FORWARDED_FOR, forwardedFor) }.bodyValue(event).retrieve()
            .onStatus({ !it.is2xxSuccessful }) { resp ->
                resp.bodyToMono<String>().defaultIfEmpty("").flatMap { body ->
                    LOG.error("Umami responded with status={} body={}", resp.statusCode().value(), body)
                    Mono.error(RuntimeException("Umami error ${resp.statusCode().value()}"))
                }
            }.toBodilessEntity()
            .retryWhen(
                Retry.backoff(3, Duration.ofMillis(500))
                    .filter { it is PrematureCloseException || (it is WebClientRequestException && it.cause is PrematureCloseException) }
                    .doBeforeRetry { LOG.warn("Retrying after connection error (attempt {})", it.totalRetries() + 1) }
            )
            .doOnSuccess { umamiRequestsSuccess.increment() }
            .doOnError { ex ->
                umamiRequestsFailure.increment()
                LOG.error("Failed to send event to Umami", ex)
            }
            .block(Duration.ofSeconds(10))
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(UmamiService::class.java)
    }
}