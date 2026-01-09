package no.nav.reops.filter

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import org.springframework.stereotype.Service

data class RedactionRule(
    val name: String,
    val regex: Regex,
    val counter: Counter,
    val preserve: Boolean
)

@Service
class FilterService(
    meterRegistry: MeterRegistry
) {
    fun filterEvent(event: Event): Event {
        val payload = event.payload
        val sanitizedPayload = payload.copy(
            website = payload.website,
            hostname = redact(payload.hostname),
            screen = redact(payload.screen),
            language = redact(payload.language),
            title = redact(payload.title),
            url = redact(payload.url),
            referrer = redact(payload.referrer)
        )
        return event.copy(payload = sanitizedPayload)
    }

    private fun redact(value: String): String {
        var current = value
        redactionRules.forEach { rule ->
            val matches = rule.regex.findAll(current).count()
            if (matches > 0) {
                rule.counter.increment(matches.toDouble())
                current = if (rule.preserve) {
                    current
                } else {
                    rule.regex.replace(current, "[REDACTED]")
                }
            }
        }

        return current
    }

    private val redactionRules: List<RedactionRule> = listOf(
        RedactionRule(
            name = "keep",
            regex = keepRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "keep")
                .register(meterRegistry),
            preserve = true
        ),
        RedactionRule(
            name = "fnr_local",
            regex = fnrLocalRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "fnr_local")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "preserve_url",
            regex = preserveUrlRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "preserve_url")
                .register(meterRegistry),
            preserve = true
        ),
        RedactionRule(
            name = "filepath",
            regex = filepathRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "filepath")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "fnr",
            regex = fnrRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "fnr")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "navident",
            regex = navidentRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "navident")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "email",
            regex = emailRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "email")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "ip",
            regex = ipRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "ip")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "phone",
            regex = phoneRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "phone")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "name",
            regex = nameRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "name")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "address",
            regex = addressRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "address")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "secret_address",
            regex = secretAddressRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "secret_address")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "account",
            regex = accountRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "account")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "org_number",
            regex = orgNumberRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "org_number")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "license_plate",
            regex = licensePlateRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "license_plate")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "search",
            regex = searchRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "search")
                .register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "uuid_preserve",
            regex = uuidPreserveRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "uuid_preserve")
                .register(meterRegistry),
            preserve = true
        ),
        RedactionRule(
            name = "url_preserve",
            regex = urlPreserveRegex(),
            counter = Counter.builder("redactions_total")
                .tag("rule", "url_preserve")
                .register(meterRegistry),
            preserve = true
        )
    )

    fun keepRegex(): Regex = Regex("((nav|test)[0-9]{6})")
    fun fnrLocalRegex(): Regex = Regex("\\b\\d{6}\\d{5}\\b")
    fun preserveUrlRegex(): Regex = Regex("https?://[A-Za-z0-9._\\-]+(?:\\.[A-Za-z0-9._\\-]+)*(?::[0-9]+)?(?:/[A-Za-z0-9._\\-/%?&=]*)?")
    fun fnrRegex(): Regex = Regex("(?<!\\d)\\d{11}(?!\\d)")
    fun navidentRegex(): Regex = Regex("(?<![a-zA-Z0-9])[a-zA-Z]\\d{6}(?!\\d)")
    fun emailRegex(): Regex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    fun ipRegex(): Regex = Regex("(?<!\\d)\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?!\\d)")
    fun phoneRegex(): Regex = Regex("(?<!\\d)[2-9]\\d{7}(?!\\d)")
    fun nameRegex(): Regex = Regex("\\b[A-ZÆØÅ][a-zæøå]{1,20}\\s[A-ZÆØÅ][a-zæøå]{1,20}(?:\\s[A-ZÆØÅ][a-zæøå]{1,20})?\\b")
    fun addressRegex(): Regex = Regex("\\b\\d{4}\\s[A-ZÆØÅ][A-ZÆØÅa-zæøå]+(?:\\s[A-ZÆØÅa-zæøå]+)*\\b")
    fun secretAddressRegex(): Regex = Regex("(?i)hemmelig(?:%20|\\s+)(?:20\\s*%(?:%20|\\s+))?adresse")
    fun accountRegex(): Regex = Regex("(?<!\\d)\\d{4}\\.?\\d{2}\\.?\\d{5}(?!\\d)")
    fun orgNumberRegex(): Regex = Regex("(?<!\\d)\\d{9}(?!\\d)")
    fun licensePlateRegex(): Regex = Regex("(?<![a-zA-Z])[A-Z]{2}\\s?\\d{5}(?!\\d)")
    fun searchRegex(): Regex = Regex("[?&](?:q|query|search|k|ord)=[^&]+")
    fun uuidPreserveRegex(): Regex = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
    fun urlPreserveRegex(): Regex = Regex(
        pattern = """(?x)
        # URLs with http/https protocol
        https?://[A-Za-z0-9._\-]+(?:\.[A-Za-z0-9._\-]+)*(?::[0-9]+)?(?:/[A-Za-z0-9._/%?&=-]*)?
        |
        # Domain-like patterns (without protocol) - must have a TLD
        # Use negative lookbehind to avoid matching email domains (no @ before)
        (?<!@)[A-Za-z0-9._\-]+\.[A-Za-z]{2,}(?:/[A-Za-z0-9._/%?&=-]+)?
        """,
        options = setOf(RegexOption.COMMENTS)
    )
    fun filepathRegex(): Regex = Regex(
        """(?x)
        (?:
          (?:[A-Za-z]:)?                      # Optional drive letter on Windows
          (?:[\\/][A-Za-z0-9._\-\s]+)+        # One or more path segments
        )
        """.trimIndent()
    )
}