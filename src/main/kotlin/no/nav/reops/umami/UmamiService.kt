package no.nav.reops.umami

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import no.nav.reops.event.USER_AGENT
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.Duration

const val X_VERCEL_IP_COUNTRY = "X-Vercel-IP-Country"
const val X_VERCEL_CITY = "X-Vercel-City"

@Service
class UmamiService(
    private val umamiClient: WebClient, meterRegistry: MeterRegistry
) {
    private val umamiRequestsSuccess: Counter =
        Counter.builder("umami_requests_total").tag("result", "success").register(meterRegistry)

    private val umamiRequestsFailure: Counter =
        Counter.builder("umami_requests_total").tag("result", "failure").register(meterRegistry)

    fun sendEvent(event: Event, userAgent: String, clientRegion: String?, clientCity: String?) {
        try {
            val country = clientRegion?.trim()?.uppercase()?.takeIf { ISO2_COUNTRY_REGEX.matches(it) }
            val city = clientCity?.trim()?.takeIf { it.isNotEmpty() }
            val req = umamiClient.post().uri("/api/send").contentType(MediaType.APPLICATION_JSON)
                .header(USER_AGENT, userAgent).apply {
                    if (country != null) header(X_VERCEL_IP_COUNTRY, country)
                    if (city != null) header(X_VERCEL_CITY, city)
                }.bodyValue(event).retrieve().onStatus(HttpStatusCode::isError) { resp ->
                    resp.bodyToMono<String>().defaultIfEmpty("").flatMap { body ->
                        LOG.error("Umami responded with status={} body={}", resp.statusCode().value(), body)
                        Mono.error(RuntimeException("Umami error ${resp.statusCode().value()}"))
                    }
                }.bodyToMono<String>().defaultIfEmpty("").doOnNext { body ->
                    LOG.info("Umami response body={}", body)
                    umamiRequestsSuccess.increment()
                }.onErrorResume { ex ->
                    umamiRequestsFailure.increment()
                    LOG.error("Failed to send event to Umami", ex)
                    Mono.empty()
                }

            req.block(Duration.ofSeconds(5))
        } catch (ex: Exception) {
            umamiRequestsFailure.increment()
            LOG.error("Failed to send event to Umami", ex)
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(UmamiService::class.java)
        private val ISO2_COUNTRY_REGEX = Regex("^[A-Z]{2}$")
    }
}
