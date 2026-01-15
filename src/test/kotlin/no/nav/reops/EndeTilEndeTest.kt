package no.nav.reops

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import no.nav.reops.event.Event
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.context.ActiveProfiles
import org.wiremock.spring.ConfigureWireMock
import org.wiremock.spring.EnableWireMock
import org.wiremock.spring.InjectWireMock
import java.time.Duration

@SpringBootTest
@ActiveProfiles("test")
@EnableWireMock(
    ConfigureWireMock(
        name = "umami-mock", port = 0
    )
)
@EmbeddedKafka(
    partitions = 1, topics = ["test-topic"], bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class EndeTilEndeTest {

    @InjectWireMock("umami-mock")
    lateinit var umamiMock: WireMockServer

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, Event>

    @Test
    fun `skal kunne konsumere hendelse på kafka`() {
        umamiMock.stubFor(
            post(urlEqualTo("/api/send")).willReturn(
                    aResponse().withStatus(200)
                        .withBody("{\"cache\":\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ3ZWJzaXRlSWQiOiIxZGJmZTRlOS1iZjhiLTQ1ZDktOTMwNS05MjhmMjIyMDBiYzAiLCJzZXNzaW9uSWQiOiIxYTU1MWEyYS1hYWU2LTU2M2QtOWFmNy1hM2JmZmFhYjFhNTMiLCJ2aXNpdElkIjoiODIyOWI4ZDYtNWUzMS01YjA1LTgzZmMtNDVjZGMwYTFkMDFkIiwiaWF0IjoxNzY4NDY5NzIwfQ.BY5xdifMYzJdhEuNP_0euSeiYSlob7cdu4qsZ548G1M\",\"sessionId\":\"1a551a2a-aae6-563d-9af7-a3bffaab1a53\",\"visitId\":\"8229b8d6-5e31-5b05-83fc-45cdc0a1d01d\"}")
                )
        )

        val event = Event(
            type = "visit", payload = Event.Payload(
                website = "https://kake.no/",
                hostname = "localhost",
                screen = "12345678901",
                language = "nb",
                title = "john.doe@kake.no",
                url = "https://kake.no/12345678901",
                referrer = "https://kake.no/"
            )
        )

        val message = MessageBuilder.withPayload(event).setHeader(KafkaHeaders.TOPIC, "test-topic")
            .setHeader(KafkaHeaders.KEY, "event-key").setHeader("User-Agent", "test-agent")
            .setHeader("X-Exclude-Filters", "").build()

        kafkaTemplate.send(message)

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            umamiMock.verify(
                postRequestedFor(urlEqualTo("/api/send")).withHeader("User-Agent", equalTo("test-agent"))
                    .withRequestBody(matchingJsonPath("$.payload.screen", equalTo("[REDACTED]")))
                    .withRequestBody(matchingJsonPath("$.payload.title", equalTo("[REDACTED]")))
                    .withRequestBody(matchingJsonPath("$.payload.url", equalTo("https:/[REDACTED]/[REDACTED]")))
                    .withRequestBody(matchingJsonPath("$.payload.referrer", equalTo("https:/[REDACTED]/")))
            )
        }
    }

	@Test
	fun `skal kunne konsumere hendelse på kafka med data`() {
		umamiMock.stubFor(
			post(urlEqualTo("/api/send")).willReturn(
				aResponse().withStatus(200)
					.withBody("{\"cache\":\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ3ZWJzaXRlSWQiOiIxZGJmZTRlOS1iZjhiLTQ1ZDktOTMwNS05MjhmMjIyMDBiYzAiLCJzZXNzaW9uSWQiOiIxYTU1MWEyYS1hYWU2LTU2M2QtOWFmNy1hM2JmZmFhYjFhNTMiLCJ2aXNpdElkIjoiODIyOWI4ZDYtNWUzMS01YjA1LTgzZmMtNDVjZGMwYTFkMDFkIiwiaWF0IjoxNzY4NDY5NzIwfQ.BY5xdifMYzJdhEuNP_0euSeiYSlob7cdu4qsZ548G1M\",\"sessionId\":\"1a551a2a-aae6-563d-9af7-a3bffaab1a53\",\"visitId\":\"8229b8d6-5e31-5b05-83fc-45cdc0a1d01d\"}")
			)
		)

		val event = Event(
			type = "visit", payload = Event.Payload(
				website = "https://kake.no/",
				hostname = "localhost",
				screen = "12345678901",
				language = "nb",
				title = "john.doe@kake.no",
				url = "https://kake.no/12345678901",
				referrer = "https://kake.no/",
				data = mapOf("hest" to "er best", "antall" to 42, "liker-hest" to true)
			)
		)

		val message = MessageBuilder.withPayload(event).setHeader(KafkaHeaders.TOPIC, "test-topic")
			.setHeader(KafkaHeaders.KEY, "event-key").setHeader("User-Agent", "test-agent")
			.setHeader("X-Exclude-Filters", "").build()

		kafkaTemplate.send(message)

		await().atMost(Duration.ofSeconds(5)).untilAsserted {
			umamiMock.verify(
				postRequestedFor(urlEqualTo("/api/send")).withHeader("User-Agent", equalTo("test-agent"))
					.withRequestBody(matchingJsonPath("$.payload.screen", equalTo("[REDACTED]")))
					.withRequestBody(matchingJsonPath("$.payload.title", equalTo("[REDACTED]")))
					.withRequestBody(matchingJsonPath("$.payload.url", equalTo("https:/[REDACTED]/[REDACTED]")))
					.withRequestBody(matchingJsonPath("$.payload.referrer", equalTo("https:/[REDACTED]/")))
			)
		}
	}
}