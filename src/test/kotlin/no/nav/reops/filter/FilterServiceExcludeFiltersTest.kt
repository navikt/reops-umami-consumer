package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilterServiceExcludeFiltersTest {

    private fun filterService(): FilterService = FilterService(SimpleMeterRegistry())

    @Test
    fun `filterEvent excludes matching keys from redaction and traversal`() {
        val service = filterService()

        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event", payload = base.payload.copy(
                data = TestEventFactory.jsonNode(
                    mapOf(
                        "user_email" to "user@example.com", "event_properties" to mapOf(
                            "email" to "deep@example.com", "ssn" to "12345678910"
                        )
                    )
                )
            )
        )

        val out = service.filterEvent(event, excludeFilters = "user_email,event_properties")
        assertEquals("user@example.com", out.payload.data!!.get("user_email").asString())

        val props = out.payload.data.get("event_properties")
        assertEquals("deep@example.com", props.get("email").asString())
        assertEquals("12345678910", props.get("ssn").asString())
    }

    @Test
    fun `filterEvent excludes top level url from redaction`() {
        val service = filterService()

        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event", payload = base.payload.copy(url = "/search?email=user@example.com&phone=98765432")
        )

        val out = service.filterEvent(event, excludeFilters = "url")

        assertEquals("/search?email=user@example.com&phone=98765432", out.payload.url)
        assertTrue(out.payload.url!!.contains("user@example.com"))
        assertTrue(out.payload.url.contains("98765432"))
    }
}