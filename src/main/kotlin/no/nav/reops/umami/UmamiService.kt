package no.nav.reops.umami

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import no.nav.reops.event.FORWARDED_FOR
import no.nav.reops.event.USER_AGENT
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class UmamiService(
    private val umamiClient: RestClient, meterRegistry: MeterRegistry
) {
    private val umamiRequestsSuccess: Counter = meterRegistry.counter("umami_requests_total", "result", "success")
    private val umamiRequestsFailure: Counter = meterRegistry.counter("umami_requests_total", "result", "failure")

    fun sendEvent(event: Event, userAgent: String, forwardedFor: String?) {
        try {
            umamiClient.post().uri("/api/send").contentType(MediaType.APPLICATION_JSON).header(USER_AGENT, userAgent)
                .apply { if (forwardedFor != null) header(FORWARDED_FOR, forwardedFor) }.body(event).retrieve()
                .toBodilessEntity()
            umamiRequestsSuccess.increment()
        } catch (ex: Exception) {
            umamiRequestsFailure.increment()
            LOG.error("Failed to send event to Umami", ex)
            throw ex
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(UmamiService::class.java)
    }
}