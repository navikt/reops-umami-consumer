package no.nav.reops.filter

import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import org.springframework.stereotype.Service
import io.micrometer.core.instrument.Counter
import org.slf4j.LoggerFactory
import kotlin.collections.plus
import java.util.concurrent.ConcurrentHashMap

@Service
class FilterService(private val meterRegistry: MeterRegistry) {
    private val keyPolicy: KeyPolicy = DefaultPolicies.keyPolicy()
    private val urlPolicy: UrlPolicy = DefaultPolicies.urlPolicy()

    fun filterEvent(event: Event, excludeFilters: String? = null): Event {
        val excludeKeys = DefaultPolicies.findExcludeKeys(excludeFilters)
        LOG.debug("Exclude filters for this event: {}", excludeKeys)

        val rules = RuleSetFactory.create(meterRegistry, event.payload.website)
        val redactor = Redactor(rules)

        val p = event.payload
        val traverser = Traverser(keyPolicy = keyPolicy, urlPolicy = urlPolicy, redactor = redactor, excludeKeys = excludeKeys)

        val sanitized = p.copy(
            website = p.website,
            id = p.id.redactedUnlessExcluded("id", excludeKeys, redactor),
            hostname = p.hostname.redactedUnlessExcluded("hostname", excludeKeys, redactor),
            screen = p.screen.redactedUnlessExcluded("screen", excludeKeys, redactor),
            language = p.language.redactedUnlessExcluded("language", excludeKeys, redactor),
            title = p.title.redactedUnlessExcluded("title", excludeKeys, redactor),
            url = p.url.redactedUnlessExcluded("url", excludeKeys, redactor),
            referrer = p.referrer.redactedUnlessExcluded("referrer", excludeKeys, redactor),
            name = p.name.redactedUnlessExcluded("name", excludeKeys, redactor),
            data = if ("data" in excludeKeys) p.data else p.data?.let { traverser.transform(it) })

        return event.copy(payload = sanitized)
    }

    private fun String?.redactedUnlessExcluded(field: String, excludeKeys: Set<String>, redactor: Redactor): String? {
        if (field in excludeKeys) return this
        return this?.let { urlPolicy.redactUrl(it, redactor) }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(FilterService::class.java)
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

    val defaultFilter: Set<String> = setOf(
        "komponent",
        "lenketekst",
        "breadcrumbs",
        "pageType",
        "pageTheme",
        "employer",
        "seksjon",
        "valg",
        "jobTitle",
        "occupationLevel2",
        "enhet",
        "filter",
        "organisasjoner",
        "destinasjon",
        "location",
        "arbeidssted",
        "kilde",
        "skjemanavn",
        "lenkegruppe",
        "linkText",
        "descriptionId",
        "tema",
        "innholdstype",
        "yrkestittel",
        "tlbhrNavn",
    )

    fun findExcludeKeys(excludeFilters: String?): Set<String> {
        return if (excludeFilters == null) {
            defaultFilter
        } else {
            parse(excludeFilters) + defaultFilter
        }
    }

    fun parse(headerValue: String?): Set<String> =
        headerValue?.split(',')?.asSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
}

internal data class RedactionRule(
    val name: String, val label: String, val regex: Regex, val counter: Counter, val preserve: Boolean = false
)

internal object RuleSetFactory {
    private val counters = ConcurrentHashMap<String, Counter>()

    fun create(meterRegistry: MeterRegistry, websiteId: java.util.UUID): List<RedactionRule> = listOf(
        keepRule(meterRegistry, websiteId),
        rule("filepath", "PROXY-FILEPATH", FilterPatterns.FILEPATH_REGEX, meterRegistry, websiteId),
        rule("fnr", "PROXY-FNR", FilterPatterns.FNR_REGEX, meterRegistry, websiteId),
        rule("navident", "PROXY-NAVIDENT", FilterPatterns.NAVIDENT_REGEX, meterRegistry, websiteId),
        rule("email", "PROXY-EMAIL", FilterPatterns.EMAIL_REGEX, meterRegistry, websiteId),
        rule("ip", "PROXY-IP", FilterPatterns.IP_REGEX, meterRegistry, websiteId),
        rule("phone", "PROXY-PHONE", FilterPatterns.PHONE_REGEX, meterRegistry, websiteId),
        rule("name", "PROXY-NAME", FilterPatterns.NAME_REGEX, meterRegistry, websiteId),
        rule("address", "PROXY-ADDRESS", FilterPatterns.ADDRESS_REGEX, meterRegistry, websiteId),
        rule("secret_address", "PROXY-SECRET-ADDRESS", FilterPatterns.SECRET_ADDRESS_REGEX, meterRegistry, websiteId),
        rule("account", "PROXY-ACCOUNT", FilterPatterns.ACCOUNT_REGEX, meterRegistry, websiteId),
        rule("org_number", "PROXY-ORG-NUMBER", FilterPatterns.ORG_NUMBER_REGEX, meterRegistry, websiteId),
        rule("license_plate", "PROXY-LICENSE-PLATE", FilterPatterns.LICENSE_PLATE_REGEX, meterRegistry, websiteId),
        rule("search", "PROXY-SEARCH", FilterPatterns.SEARCH_REGEX, meterRegistry, websiteId)
    )

    private fun keepRule(meterRegistry: MeterRegistry, websiteId: java.util.UUID): RedactionRule = RedactionRule(
        name = "keep",
        label = "PROXY-KEEP",
        regex = FilterPatterns.KEEP_REGEX,
        counter = counter(meterRegistry, "keep", websiteId),
        preserve = true
    )

    private fun rule(
        name: String, label: String, regex: Regex, meterRegistry: MeterRegistry, websiteId: java.util.UUID
    ): RedactionRule = RedactionRule(
        name = name, label = label, regex = regex, counter = counter(meterRegistry, name, websiteId), preserve = false
    )

    private fun counter(meterRegistry: MeterRegistry, ruleName: String, websiteId: java.util.UUID): Counter {
        val key = "redactions_total|rule=$ruleName|websiteId=$websiteId"
        return counters.computeIfAbsent(key) {
            Counter.builder("redactions_total").tag("rule", ruleName).tag("websiteId", websiteId.toString())
                .register(meterRegistry)
        }
    }
}
