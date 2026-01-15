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
            type = "event",
            payload = base.payload.copy(
                data = mapOf(
                    // would normally be redacted, but excluded
                    "user_email" to "user@example.com",
                    // nested map would normally be traversed and redacted, but excluded as a whole
                    "event_properties" to mapOf(
                        "email" to "deep@example.com",
                        "ssn" to "12345678901"
                    )
                )
            )
        )

        val out = service.filterEvent(event, excludeFilters = setOf("user_email", "event_properties"))

        // user_email preserved
        assertEquals("user@example.com", (out.payload.data!!["user_email"] as String))

        // event_properties preserved as-is (no traversal/redaction inside)
        val props = out.payload.data["event_properties"] as Map<*, *>
        assertEquals("deep@example.com", props["email"])
        assertEquals("12345678901", props["ssn"])
    }

    @Test
    fun `filterEvent excludes top level url from redaction`() {
        val service = filterService()

        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event",
            payload = base.payload.copy(url = "/search?email=user@example.com&phone=98765432")
        )

        val out = service.filterEvent(event, excludeFilters = setOf("url"))

        // No query redaction should happen when url is excluded.
        assertEquals("/search?email=user@example.com&phone=98765432", out.payload.url)
        assertTrue(out.payload.url.contains("user@example.com"))
        assertTrue(out.payload.url.contains("98765432"))
    }
}
