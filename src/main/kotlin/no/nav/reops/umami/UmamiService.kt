package no.nav.reops.umami

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import no.nav.reops.event.FORWARDED_FOR
import no.nav.reops.event.USER_AGENT
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

@Service
class UmamiService(
    private val umamiClient: WebClient, private val meterRegistry: MeterRegistry
) {
    private val counters = ConcurrentHashMap<String, Counter>()

    fun sendEvent(event: Event, userAgent: String, forwardedFor: String?) {
        val websiteId = event.payload.website.toString()
        val successCounter = counter("umami_requests_total", "success", websiteId)
        val failureCounter = counter("umami_requests_total", "failure", websiteId)

        umamiClient.post().uri("/api/send").contentType(MediaType.APPLICATION_JSON).header(USER_AGENT, userAgent)
            .apply { if (forwardedFor != null) header(FORWARDED_FOR, forwardedFor) }.bodyValue(event).retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                resp.bodyToMono<String>().defaultIfEmpty("").flatMap { body ->
                    LOG.error("Umami responded with status={} body={}", resp.statusCode().value(), body)
                    Mono.error(RuntimeException("Umami error ${resp.statusCode().value()}"))
                }
            }.toBodilessEntity().doOnSuccess { successCounter.increment() }.doOnError { ex ->
                failureCounter.increment()
                LOG.error("Failed to send event to Umami", ex)
            }.onErrorResume { Mono.empty() }.subscribe()
    }

    private fun counter(name: String, result: String, websiteId: String): Counter {
        val key = "$name|result=$result|websiteId=$websiteId"
        return counters.computeIfAbsent(key) {
            meterRegistry.counter(name, "result", result, "websiteId", websiteId)
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(UmamiService::class.java)
    }
}