package no.nav.reops.filter

import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import org.springframework.stereotype.Service
import io.micrometer.core.instrument.Counter

@Service
class FilterService(meterRegistry: MeterRegistry) {
    private val rules: List<RedactionRule> = RuleSetFactory.create(meterRegistry)
    private val keyPolicy: KeyPolicy = DefaultPolicies.keyPolicy()
    private val urlPolicy: UrlPolicy = DefaultPolicies.urlPolicy()
    private val redactor = Redactor(rules)

    fun filterEvent(event: Event, excludeFilters: Set<String> = emptySet()): Event {
        val p = event.payload
        val traverser = Traverser(
            keyPolicy = keyPolicy, urlPolicy = urlPolicy, redactor = redactor, excludeFilters = excludeFilters
        )

        val sanitized = p.copy(
            website = p.website,
            hostname = p.hostname.redactedUnlessExcluded("hostname", excludeFilters),
            screen = p.screen.redactedUnlessExcluded("screen", excludeFilters),
            language = p.language.redactedUnlessExcluded("language", excludeFilters),
            title = p.title.redactedUnlessExcluded("title", excludeFilters),
            url = p.url.redactedUnlessExcluded("url", excludeFilters),
            referrer = p.referrer.redactedUnlessExcluded("referrer", excludeFilters),
            name = p.name.redactedUnlessExcluded("name", excludeFilters),
            data = if ("data" in excludeFilters) p.data else p.data?.let { traverser.transform(it) })

        return event.copy(payload = sanitized)
    }

    private fun String?.redactedUnlessExcluded(field: String, excludeFilters: Set<String>): String? {
        if (field in excludeFilters) return this
        return this?.let { urlPolicy.redactUrl(it, redactor) }
    }
}

internal object DefaultPolicies {
    fun keyPolicy(): KeyPolicy = KeyPolicy(
        preservedKeys = setOf("api_key", "device_id", "website"),
        droppedKeys = setOf("ip_address"),
        forcedValues = mapOf("ip" to "\$remote"),
        advertisingIdKeys = FilterPatterns.ADVERTISING_ID_KEYS
    )

    fun urlPolicy(): UrlPolicy = UrlPolicy(
        isNestedUrlField = { ctx ->
            ctx.depth == 2 && ctx.containerKey == "payload" && (ctx.key == "url" || ctx.key == "referrer")
        }, urlLikeKeys = setOf(
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
}

internal data class RedactionRule(
    val name: String, val label: String, val regex: Regex, val counter: Counter, val preserve: Boolean = false
)

internal object RuleSetFactory {
    fun create(meterRegistry: MeterRegistry): List<RedactionRule> = listOf(
        keepRule(meterRegistry),
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

    private fun keepRule(meterRegistry: MeterRegistry): RedactionRule = RedactionRule(
        name = "keep",
        label = "PROXY-KEEP",
        regex = FilterPatterns.KEEP_REGEX,
        counter = counter(meterRegistry, "keep"),
        preserve = true
    )

    private fun rule(name: String, label: String, regex: Regex, meterRegistry: MeterRegistry): RedactionRule =
        RedactionRule(
            name = name, label = label, regex = regex, counter = counter(meterRegistry, name), preserve = false
        )

    private fun counter(meterRegistry: MeterRegistry, ruleName: String): Counter =
        Counter.builder("redactions_total").tag("rule", ruleName).register(meterRegistry)
}
