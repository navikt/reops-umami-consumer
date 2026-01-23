package no.nav.reops.umami

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.reops.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.UUID

class UmamiServiceTest {

    @ParameterizedTest(name = "status={0} => success={1}, failure={2}")
    @CsvSource(
        "200, 1.0, 0.0",
        "300, 1.0, 0.0",
        "400, 0.0, 1.0",
        "500, 0.0, 1.0",
    )
    fun `umami_requests_total counts success and failure correctly`(
        status: Int,
        expectedSuccess: Double,
        expectedFailure: Double,
    ) {
        val registry = SimpleMeterRegistry()
        val webClient = webClient(status = status, body = "test-body", contentType = MediaType.TEXT_PLAIN_VALUE)
        val service = UmamiService(webClient, registry)
        val forwardedFor = "127.0.0.1"
        val userAgent = "Mozilla/5.0"
        val event = Event(
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

        service.sendEvent(event = event, userAgent = userAgent, forwardedFor = forwardedFor)

        assertEquals(expectedSuccess, successCounter(registry))
        assertEquals(expectedFailure, failureCounter(registry))
    }

    private fun webClient(status: Int, body: String, contentType: String): WebClient {
        val exchangeFunction = ExchangeFunction { _ ->
            Mono.just(clientResponse(status, body, contentType))
        }
        return WebClient.builder().exchangeFunction(exchangeFunction).build()
    }

    private fun clientResponse(status: Int, body: String, contentType: String): ClientResponse {
        return ClientResponse.create(HttpStatusCode.valueOf(status)).header("Content-Type", contentType).body(body)
            .build()
    }

    private fun successCounter(registry: SimpleMeterRegistry): Double =
        registry.find("umami_requests_total").tag("result", "success").counter()?.count() ?: 0.0

    private fun failureCounter(registry: SimpleMeterRegistry): Double =
        registry.find("umami_requests_total").tag("result", "failure").counter()?.count() ?: 0.0
}