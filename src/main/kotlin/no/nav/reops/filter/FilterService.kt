package no.nav.reops.filter

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import org.springframework.stereotype.Service

data class RedactionRule(
    val name: String,
    val label: String,
    val regex: Regex,
    val counter: Counter,
    val preserve: Boolean = false
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
        // Only nested data.payload.url/referrer are treated as URL fields in traversal.
        // payload.url/referrer are handled directly in filterEvent().
        isNestedUrlField = { ctx ->
            ctx.depth == 2 &&
                    ctx.containerKey == "payload" &&
                    (ctx.key == "url" || ctx.key == "referrer")
        },
        // URL path-part exclusions required by tests
        pathExcludedLabels = setOf("PROXY-FILEPATH", "PROXY-FNR", "PROXY-ACCOUNT")
    )

    private val redactor = Redactor(rules)

    /**
     * Filters an event with optional per-event key exclusions.
     *
     * excludeFilters: set of keys (e.g. "hostname", "screen", "language", "url")
     * If a key matches:
     *  - its value is not redacted
     *  - and for map values, traversal is not performed under that key
     */
    fun filterEvent(event: Event, excludeFilters: Set<String> = emptySet()): Event {
        val p = event.payload

        // Traverser is per-event because excludeFilters is per event/header.
        val traverser = Traverser(keyPolicy, urlPolicy, redactor, excludeFilters)

        val sanitized = p.copy(
            website = p.website,

            // These are currently preserved by design, but excludeFilters is applied anyway
            // so behavior stays correct if you later decide to redact them.
            hostname = if ("hostname" in excludeFilters) p.hostname else p.hostname,
            screen = if ("screen" in excludeFilters) p.screen else p.screen,
            language = if ("language" in excludeFilters) p.language else p.language,
            title = if ("title" in excludeFilters) p.title else p.title,

            // URL policy for top-level payload URLs, unless excluded
            url = if ("url" in excludeFilters) p.url else urlPolicy.redactUrl(p.url, redactor),
            referrer = if ("referrer" in excludeFilters) p.referrer else urlPolicy.redactUrl(p.referrer, redactor),

            // If someone excludes "data", keep it unchanged.
            data = if ("data" in excludeFilters) {
                p.data
            } else {
                p.data?.let { traverser.transform(it) as? Map<String, Any?> ?: it }
            }
        )

        return event.copy(payload = sanitized)
    }

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