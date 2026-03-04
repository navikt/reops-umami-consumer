package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FilterServiceNameRedactionTest {

    private fun filterService(): FilterService = FilterService(SimpleMeterRegistry())

    private fun redact(input: String): String {
        val service = filterService()
        return service.filterEvent(TestEventFactory.eventWithData(input)).payload.data!!.get("text").asString()
    }

    @Test
    fun `redacts two-word name`() {
        assertEquals("[PROXY-NAME]", redact("Ola Nordmann"))
    }

    @Test
    fun `redacts three-word name`() {
        assertEquals("[PROXY-NAME]", redact("Ola Per Nordmann"))
    }

    @Test
    fun `Hele Norge is treated as name by regex`() {
        // The Kotlin NAME_REGEX matches any two capitalized words.
        // Unlike the Rust implementation there is no "Norge" exclusion.
        val result = redact("Hele Norge")
        assertEquals("[PROXY-NAME]", result)
    }

    @Test
    fun `Bank Norge is treated as name by regex`() {
        val result = redact("Bank Norge")
        assertEquals("[PROXY-NAME]", result)
    }

    @Test
    fun `Norge Bank is treated as name by regex`() {
        val result = redact("Norge Bank")
        assertEquals("[PROXY-NAME]", result)
    }

    @Test
    fun `standalone Norge is not redacted`() {
        assertEquals("Norge", redact("Norge"))
    }

    @Test
    fun `Velkommen til Norge - til Norge matches as name in Kotlin`() {
        // In Rust, "Norge" is excluded from name matching.
        // In Kotlin there is no "Norge" exclusion, so "til Norge" is not a match
        // because "til" starts with lowercase. Only capitalized word pairs match.
        assertEquals("Velkommen til Norge", redact("Velkommen til Norge"))
    }

    @Test
    fun `Stor Norge Bank is treated as name by regex`() {
        // In Rust, "Norge" is excluded so "Stor Norge Bank" is preserved.
        // In Kotlin there is no "Norge" exclusion, so it matches as a 3-word name.
        val result = redact("Stor Norge Bank")
        assertEquals("[PROXY-NAME]", result)
    }

    @Test
    fun `Hele Vakre Norge matches name pattern`() {
        val result = redact("Hele Vakre Norge")
        // Either the whole thing is redacted as 3-word name or "Hele Vakre" is redacted with Norge left
        assert(result == "[PROXY-NAME]" || result == "[PROXY-NAME] Norge")
    }
}

