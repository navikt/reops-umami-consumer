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
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Duration
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
@EnableWireMock(ConfigureWireMock(name = "umami-mock", port = 0))
@EmbeddedKafka(
    partitions = 1, topics = ["test-topic"], bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class EndeTilEndeTest {

    @InjectWireMock("umami-mock")
    lateinit var umamiMock: WireMockServer

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, Event>

    @Test
    fun `skal kunne konsumere hendelse på kafka uten data`() {
        umamiMock.stubFor(
            post(urlEqualTo("/api/send")).willReturn(
                aResponse().withStatus(200).withBody(
                        "{\"cache\":\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ3ZWJzaXRlSWQiOiIxZGJmZTRlOS1iZjhiLTQ1ZDktOTMwNS05MjhmMjIyMDBiYzAiLCJzZXNzaW9uSWQiOiIxYTU1MWEyYS1hYWU2LTU2M2QtOWFmNy1hM2JmZmFhYjFhNTMiLCJ2aXNpdElkIjoiODIyOWI4ZDYtNWUzMS01YjA1LTgzZmMtNDVjZGMwYTFkMDFkIiwiaWF0IjoxNzY4NDY5NzIwfQ.BY5xdifMYzJdhEuNP_0euSeiYSlob7cdu4qsZ548G1M\",\"sessionId\":\"1a551a2a-aae6-563d-9af7-a3bffaab1a53\",\"visitId\":\"8229b8d6-5e31-5b05-83fc-45cdc0a1d01d\"}"
                    )
            )
        )

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

        val message = MessageBuilder.withPayload(event).setHeader(KafkaHeaders.TOPIC, "test-topic")
            .setHeader(KafkaHeaders.KEY, "event-key").setHeader("User-Agent", "test-agent")
            .setHeader("X-Exclude-Filters", "").build()

        kafkaTemplate.send(message)

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            umamiMock.verify(
                postRequestedFor(urlEqualTo("/api/send")).withHeader("User-Agent", equalTo("test-agent"))
                    .withRequestBody(notContaining("12345678910")).withRequestBody(notContaining("john.doe@kake.no"))
                    .withRequestBody(containing("[PROXY-EMAIL]"))
                    .withRequestBody(containing("https://kake.no/[PROXY-FNR]"))
                    .withRequestBody(containing("https://kake.no/"))
            )
        }
    }

    @Test
    fun `skal kunne konsumere hendelse på kafka med data`() {
        umamiMock.stubFor(
            post(urlEqualTo("/api/send")).willReturn(
                aResponse().withStatus(200).withBody(
                        "{\"cache\":\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ3ZWJzaXRlSWQiOiIxZGJmZTRlOS1iZjhiLTQ1ZDktOTMwNS05MjhmMjIyMDBiYzAiLCJzZXNzaW9uSWQiOiIxYTU1MWEyYS1hYWU2LTU2M2QtOWFmNy1hM2JmZmFhYjFhNTMiLCJ2aXNpdElkIjoiODIyOWI4ZDYtNWUzMS01YjA1LTgzZmMtNDVjZGMwYTFkMDFkIiwiaWF0IjoxNzY4NDY5NzIwfQ.BY5xdifMYzJdhEuNP_0euSeiYSlob7cdu4qsZ548G1M\",\"sessionId\":\"1a551a2a-aae6-563d-9af7-a3bffaab1a53\",\"visitId\":\"8229b8d6-5e31-5b05-83fc-45cdc0a1d01d\"}"
                    )
            )
        )

        val data =
            JsonNodeFactory.instance.objectNode().put("hest", "er best").put("antall", 42).put("liker-hest", true)

        val event = Event(
            type = "visit", payload = Event.Payload(
                website = UUID.randomUUID(),
                hostname = "localhost",
                screen = "12345678910",
                language = "nb",
                title = "john.doe@kake.no",
                url = "https://kake.no/12345678910",
                referrer = "https://kake.no/",
                data = data
            )
        )

        val message = MessageBuilder.withPayload(event).setHeader(KafkaHeaders.TOPIC, "test-topic")
            .setHeader(KafkaHeaders.KEY, "event-key").setHeader("User-Agent", "test-agent")
            .setHeader("X-Exclude-Filters", "").build()

        kafkaTemplate.send(message)

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            umamiMock.verify(
                postRequestedFor(urlEqualTo("/api/send")).withHeader("User-Agent", equalTo("test-agent"))
                    .withRequestBody(notContaining("12345678910")).withRequestBody(notContaining("john.doe@kake.no"))
                    .withRequestBody(containing("[PROXY-EMAIL]"))
                    .withRequestBody(containing("https://kake.no/[PROXY-FNR]"))
                    .withRequestBody(containing("https://kake.no/")).withRequestBody(containing("\"hest\""))
                    .withRequestBody(containing("\"antall\"")).withRequestBody(containing("\"liker-hest\""))
            )
        }
    }
}