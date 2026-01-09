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
		name = "umami-mock",
		port = 0
	)
)
@EmbeddedKafka(
	partitions = 1,
	topics = ["test-topic"],
	bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class EndeTilEndeTest {

	@InjectWireMock("umami-mock")
	lateinit var umamiMock: WireMockServer

	@Autowired
	lateinit var kafkaTemplate: KafkaTemplate<String, Event>

	@Test
	fun `skal kunne konsumere hendelse på kafka`() {
		umamiMock.stubFor(
			post(urlEqualTo("/api/send"))
				.willReturn(aResponse().withStatus(200).withBody("ok"))
		)

		val event = Event(
			type = "visit",
			payload = Event.Payload(
				website = "https://reops.no/search?q=secret",
				hostname = "localhost",
				screen = "12345678901",
				language = "nb",
				title = "john.doe@example.com",
				url = "https://app.nav.no/12345678901",
				referrer = "https://example.com/"
			)
		)

		val message = MessageBuilder.withPayload(event)
			.setHeader(KafkaHeaders.TOPIC, "test-topic")
			.setHeader(KafkaHeaders.KEY, "event-key")
			.setHeader("User-Agent", "test-agent")
			.build()

		kafkaTemplate.send(message)

		await().atMost(Duration.ofSeconds(10)).untilAsserted {
			umamiMock.verify(
				postRequestedFor(urlEqualTo("/api/send"))
					.withHeader("User-Agent", equalTo("test-agent"))
					.withRequestBody(matchingJsonPath("$.payload.screen", equalTo("[REDACTED]")))
					.withRequestBody(matchingJsonPath("$.payload.title", equalTo("[REDACTED]")))
					.withRequestBody(matchingJsonPath("$.payload.url", equalTo("https:/[REDACTED]/[REDACTED]")))
					.withRequestBody(matchingJsonPath("$.payload.referrer", equalTo("https:/[REDACTED]/")))
			)
		}
	}
}