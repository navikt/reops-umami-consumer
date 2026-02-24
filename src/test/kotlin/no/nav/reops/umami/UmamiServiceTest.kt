package no.nav.reops.umami

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.reops.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpMethod
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestClient
import java.util.UUID

class UmamiServiceTest {

    @ParameterizedTest(name = "status={0} => success counter incremented")
    @CsvSource("200", "300")
    fun `umami_requests_total counts success correctly`(status: Int) {
        val registry = SimpleMeterRegistry()
        val builder = RestClient.builder().baseUrl("http://localhost")
        val mockServer = MockRestServiceServer.bindTo(builder).build()
        val service = UmamiService(builder.build(), registry)

        mockServer.expect(requestTo("http://localhost/api/send")).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)))

        service.sendEvent(event = sampleEvent(), userAgent = "Mozilla/5.0", forwardedFor = "127.0.0.1")

        mockServer.verify()
        assertEquals(1.0, successCounter(registry))
        assertEquals(0.0, failureCounter(registry))
    }

    @ParameterizedTest(name = "status={0} => failure counter incremented and exception thrown")
    @CsvSource("400", "500")
    fun `umami_requests_total counts failure correctly and throws exception`(status: Int) {
        val registry = SimpleMeterRegistry()
        val builder = RestClient.builder().baseUrl("http://localhost")
        val mockServer = MockRestServiceServer.bindTo(builder).build()
        val service = UmamiService(builder.build(), registry)

        mockServer.expect(requestTo("http://localhost/api/send")).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)))

        assertThrows<Exception> {
            service.sendEvent(event = sampleEvent(), userAgent = "Mozilla/5.0", forwardedFor = "127.0.0.1")
        }

        mockServer.verify()
        assertEquals(0.0, successCounter(registry))
        assertEquals(1.0, failureCounter(registry))
    }

    private fun sampleEvent(): Event = Event(
        type = "visit", payload = Event.Payload(
            website = UUID.randomUUID(),
            hostname = "localhost",
            screen = "12345678910",
            language = "nb",
            title = "john.doe@kake.no",
            url = "https://kake.no/12345678910",
            referrer = "https://kake.no/"
        )
    )


    private fun successCounter(registry: SimpleMeterRegistry): Double =
        registry.find("umami_requests_total").tag("result", "success").counter()?.count() ?: 0.0

    private fun failureCounter(registry: SimpleMeterRegistry): Double =
        registry.find("umami_requests_total").tag("result", "failure").counter()?.count() ?: 0.0
}