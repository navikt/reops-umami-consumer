package no.nav.reops.event

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class Event(
    val type: String,
    val payload: Payload
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Payload(
        val website: String,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val hostname: String? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val screen: String? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val language: String? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val title: String? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val url: String? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val referrer: String? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val data: JsonNode? = null
    ) {
        init {
            require(website.isNotBlank()) { "payload.website must not be blank" }
        }

        companion object {
            private fun String?.nullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

            @JvmStatic
            @JsonCreator
            fun fromJson(
                @JsonProperty("website") website: String,
                @JsonProperty("hostname") hostname: String?,
                @JsonProperty("screen") screen: String?,
                @JsonProperty("language") language: String?,
                @JsonProperty("title") title: String?,
                @JsonProperty("url") url: String?,
                @JsonProperty("referrer") referrer: String?,
                @JsonProperty("data") data: JsonNode?
            ): Payload =
                Payload(
                    website = website.trim().also { require(it.isNotBlank()) { "payload.website must not be blank" } },
                    hostname = hostname.nullIfBlank(),
                    screen = screen.nullIfBlank(),
                    language = language.nullIfBlank(),
                    title = title.nullIfBlank(),
                    url = url.nullIfBlank(),
                    referrer = referrer.nullIfBlank(),
                    data = data
                )
        }
    }
}