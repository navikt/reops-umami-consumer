package no.nav.reops.filter

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import org.springframework.stereotype.Service

data class RedactionRule(
    val name: String, val label: String, val regex: Regex, val counter: Counter, val preserve: Boolean = false
)

@Service
class FilterService(
    meterRegistry: MeterRegistry
) {
    private val rules: List<RedactionRule> = buildRules(meterRegistry)

    private val keyPolicy = KeyPolicy(
        preservedKeys = setOf("api_key", "device_id", "website"),
        droppedKeys = setOf("ip_address"),
        forcedValues = mapOf("ip" to "\$remote"),
        advertisingIdKeys = FilterPatterns.ADVERTISING_ID_KEYS
    )

    private val urlPolicy = UrlPolicy(
        isNestedUrlField = { ctx ->
            ctx.depth == 2 && ctx.containerKey == "payload" && (ctx.key == "url" || ctx.key == "referrer")
        },

        urlLikeKeys = setOf(
            "path",
            "href",
            "destinasjon",
            "url",
            "link",
            "pathname",
            "linkText",
            "destination",
            "url_path",
            "fra",
            "lenketekst",
            "lenkesti",
            "newLocation",
            "prevLocation"
        ), pathExcludedLabels = setOf("PROXY-FILEPATH")
    )

    private val redactor = Redactor(rules)

    fun filterEvent(event: Event, excludeFilters: Set<String> = emptySet()): Event {
        val p = event.payload
        val traverser = Traverser(keyPolicy, urlPolicy, redactor, excludeFilters)
        val sanitized = p.copy(
            website = p.website,
            hostname = if ("hostname" in excludeFilters) p.hostname else redactField(p.hostname),
            screen = if ("screen" in excludeFilters) p.screen else redactField(p.screen),
            language = if ("language" in excludeFilters) p.language else redactField(p.language),
            title = if ("title" in excludeFilters) p.title else redactField(p.title),
            url = if ("url" in excludeFilters) p.url else redactField(p.url),
            referrer = if ("referrer" in excludeFilters) p.referrer else redactField(p.referrer),
            data = if ("data" in excludeFilters) p.data else p.data?.let { traverser.transform(it) })

        return event.copy(payload = sanitized)
    }

    private fun redactField(value: String?): String? = value?.let { urlPolicy.redactUrl(it, redactor) }

    private fun buildRules(meterRegistry: MeterRegistry): List<RedactionRule> = listOf(
        RedactionRule(
            name = "keep",
            label = "PROXY-KEEP",
            regex = FilterPatterns.KEEP_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "keep").register(meterRegistry),
            preserve = true
        ),
        rule("filepath", "PROXY-FILEPATH", FilterPatterns.FILEPATH_REGEX, meterRegistry),
        rule("fnr", "PROXY-FNR", FilterPatterns.FNR_REGEX, meterRegistry),
        rule("navident", "PROXY-NAVIDENT", FilterPatterns.NAVIDENT_REGEX, meterRegistry),
        rule("email", "PROXY-EMAIL", FilterPatterns.EMAIL_REGEX, meterRegistry),
        rule("ip", "PROXY-IP", FilterPatterns.IP_REGEX, meterRegistry),
        rule("phone", "PROXY-PHONE", FilterPatterns.PHONE_REGEX, meterRegistry),
        rule("name", "PROXY-NAME", FilterPatterns.NAME_REGEX, meterRegistry),
        rule("address", "PROXY-ADDRESS", FilterPatterns.ADDRESS_REGEX, meterRegistry),
        rule("secret_address", "PROXY-SECRET-ADDRESS", FilterPatterns.SECRET_ADDRESS_REGEX, meterRegistry),
        rule("account", "PROXY-ACCOUNT", FilterPatterns.ACCOUNT_REGEX, meterRegistry),
        rule("org_number", "PROXY-ORG-NUMBER", FilterPatterns.ORG_NUMBER_REGEX, meterRegistry),
        rule("license_plate", "PROXY-LICENSE-PLATE", FilterPatterns.LICENSE_PLATE_REGEX, meterRegistry),
        rule("search", "PROXY-SEARCH", FilterPatterns.SEARCH_REGEX, meterRegistry)
    )

    private fun rule(name: String, label: String, regex: Regex, meterRegistry: MeterRegistry): RedactionRule =
        RedactionRule(
            name = name,
            label = label,
            regex = regex,
            counter = Counter.builder("redactions_total").tag("rule", name).register(meterRegistry)
        )
}