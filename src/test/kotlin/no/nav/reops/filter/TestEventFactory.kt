package no.nav.reops.filter

import no.nav.reops.event.Event

internal object TestEventFactory {
    fun minimalEvent(): Event {
        return Event(
            type = "visit",
            payload = Event.Payload(
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
    }

    fun eventWithData(text: String): Event {
        val base = minimalEvent()
        return base.copy(payload = base.payload.copy(data = mapOf("text" to text)))
    }
}
