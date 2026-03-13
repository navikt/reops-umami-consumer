package no.nav.reops

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import no.nav.reops.event.FORWARDED_FOR
import no.nav.reops.event.USER_AGENT
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.context.ActiveProfiles
import org.wiremock.spring.ConfigureWireMock
import org.wiremock.spring.EnableWireMock
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
@EnableWireMock(ConfigureWireMock(name = "umami-mock", port = 0))
@EmbeddedKafka(
    partitions = 1,
    topics = ["test-topic", "test-topic-retry", "test-topic-dlt"],
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class DltHandlerTest {

    @InjectWireMock("umami-mock")
    lateinit var umamiMock: WireMockServer

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, Event>

    @Autowired
    lateinit var registry: KafkaListenerEndpointRegistry

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @BeforeEach
    fun waitForKafkaListener() {
        registry.listenerContainers.forEach { container ->
            ContainerTestUtils.waitForAssignment(container, 1)
        }
    }

    @Test
    fun `DLT handler is invoked after retries are exhausted`() {
        // Umami always returns 500 -> eventListen throws -> retries exhausted -> DLT
        umamiMock.stubFor(
            post(urlEqualTo("/api/send")).willReturn(aResponse().withStatus(500))
        )

        val event = Event(
            type = "visit", payload = Event.Payload(
                website = UUID.randomUUID(),
                hostname = "localhost",
                screen = "1920x1080",
                language = "nb",
                title = "Test",
                url = "/test",
                referrer = ""
            )
        )

        val message = MessageBuilder.withPayload(event)
            .setHeader(KafkaHeaders.TOPIC, "test-topic")
            .setHeader(KafkaHeaders.KEY, "dlt-test-key")
            .setHeader(USER_AGENT, "test-agent")
            .setHeader(FORWARDED_FOR, "127.0.0.1")
            .build()

        kafkaTemplate.send(message)

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted {
            val dltCount = meterRegistry.find("kafka_events_dlt_total").counter()
            assertThat(dltCount).isNotNull
            assertThat(dltCount!!.count()).isGreaterThanOrEqualTo(1.0)
        }
    }
}

