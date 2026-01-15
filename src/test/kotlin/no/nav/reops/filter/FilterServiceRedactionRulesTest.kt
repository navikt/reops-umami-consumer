package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class FilterServiceRedactionRulesTest {

    private fun filterService(): FilterService = FilterService(SimpleMeterRegistry())

    @Test
    fun `filterEvent redacts non preserved patterns in data`() {
        val service = filterService()

        val event = TestEventFactory.eventWithData("fnr=12345678910")
        val out = service.filterEvent(event)

        val outText = out.payload.data.toString()
        assertNotEquals(event.payload.data.toString(), outText)
        assertEquals(true, outText.contains("[PROXY-FNR]"))
    }

    @Test
    fun `filterEvent preserves allowed values in data`() {
        val service = filterService()

        val keep = "nav123456 test654321"
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val url1 = "https://example.com/path?q=1"
        val url2 = "example.com/path"

        val value = "keep=$keep uuid=$uuid url1=$url1 url2=$url2"
        val event = TestEventFactory.eventWithData(value)

        val out = service.filterEvent(event)
        val outText = out.payload.data.toString()

        assertEquals(true, outText.contains(keep))
        assertEquals(true, outText.contains(uuid))
        assertEquals(true, outText.contains("url1=https://example.com/path?q=1"))
        assertEquals(true, outText.contains("url2=example.com/path"))
    }

    @Test
    fun `filterEvent preserves keep patterns navident like values`() {
        val service = filterService()

        val out1 = service.filterEvent(TestEventFactory.eventWithData("nav123456"))
        assertEquals("nav123456", out1.payload.data!!.get("text").asString())

        val out2 = service.filterEvent(TestEventFactory.eventWithData("test654321"))
        assertEquals("test654321", out2.payload.data!!.get("text").asString())
    }

    @Test
    fun `filterEvent redacts fnr in string value`() {
        val service = filterService()

        val out = service.filterEvent(TestEventFactory.eventWithData("23031510135"))
        assertEquals("[PROXY-FNR]", out.payload.data!!.get("text").asString())
    }

    @Test
    fun `filterEvent redacts fnr variants inside text`() {
        val service = filterService()

        val out1 = service.filterEvent(TestEventFactory.eventWithData("my_fnr_23031510135"))
        assertEquals("my_fnr_[PROXY-FNR]", out1.payload.data!!.get("text").asString())

        val out2 = service.filterEvent(TestEventFactory.eventWithData("my-fnr:23031510135 it's nice"))
        assertEquals("my-fnr:[PROXY-FNR] it's nice", out2.payload.data!!.get("text").asString())

        val out3 = service.filterEvent(TestEventFactory.eventWithData("my-fnr-23031510135"))
        assertEquals("my-fnr-[PROXY-FNR]", out3.payload.data!!.get("text").asString())
    }

    @Test
    fun `filterEvent keeps non matching strings unchanged`() {
        val service = filterService()

        val out1 = service.filterEvent(TestEventFactory.eventWithData("regularstring"))
        assertEquals("regularstring", out1.payload.data!!.get("text").asString())

        val out2 = service.filterEvent(TestEventFactory.eventWithData("anotherString"))
        assertEquals("anotherString", out2.payload.data!!.get("text").asString())

        val out3 = service.filterEvent(TestEventFactory.eventWithData("12345"))
        assertEquals("12345", out3.payload.data!!.get("text").asString())
    }

    @Test
    fun `filterEvent redacts pii fields recursively in data`() {
        val service = filterService()

        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event",
            payload = base.payload.copy(
                data = TestEventFactory.jsonNode(
                    mapOf(
                        "user_email" to "user@example.com",
                        "user_id" to "550e8400-e29b-41d4-a716-446655440000",
                        "ssn" to "12345678901",
                        "phone" to "98765432",
                        "ip_address" to "192.168.1.1",
                        "event_properties" to mapOf(
                            "card_number" to "1234 5678 9012 3456",
                            "account" to "1234.56.78901",
                            "navident" to "X123456",
                            "regular_field" to "This is normal text"
                        )
                    )
                )
            )
        )

        val out = service.filterEvent(event)

        val expected = event.copy(
            payload = event.payload.copy(
                data = TestEventFactory.jsonNode(
                    mapOf(
                        "user_email" to "[PROXY-EMAIL]",
                        "user_id" to "550e8400-e29b-41d4-a716-446655440000",
                        "ssn" to "[PROXY-FNR]",
                        "phone" to "[PROXY-PHONE]",
                        "event_properties" to mapOf(
                            "card_number" to "1234 5678 9012 3456",
                            "account" to "[PROXY-ACCOUNT]",
                            "navident" to "[PROXY-NAVIDENT]",
                            "regular_field" to "This is normal text"
                        )
                    )
                )
            )
        )

        assertEquals(expected, out)
    }
}