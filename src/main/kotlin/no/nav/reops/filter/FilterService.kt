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
        for (rule in redactionRules) {
            val found = rule.regex.containsMatchIn(current)
            if (found) {
                if (!rule.preserve) {
                    val replaced = rule.regex.replace(current, "[REDACTED]")
                    if (replaced !== current) {
                        rule.counter.increment()
                        current = replaced
                    }
                } else {
                    rule.counter.increment()
                }
            }
        }
        return current
    }

    private val redactionRules: List<RedactionRule> = listOf(
        RedactionRule(
            name = "keep",
            regex = KEEP_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "keep").register(meterRegistry),
            preserve = true
        ),
        RedactionRule(
            name = "fnr_local",
            regex = FNR_LOCAL_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "fnr_local").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "filepath",
            regex = FILEPATH_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "filepath").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "fnr",
            regex = FNR_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "fnr").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "navident",
            regex = NAVIDENT_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "navident").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "email",
            regex = EMAIL_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "email").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "ip",
            regex = IP_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "ip").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "phone",
            regex = PHONE_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "phone").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "name",
            regex = NAME_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "name").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "address",
            regex = ADDRESS_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "address").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "secret_address",
            regex = SECRET_ADDRESS_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "secret_address").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "account",
            regex = ACCOUNT_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "account").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "org_number",
            regex = ORG_NUMBER_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "org_number").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "license_plate",
            regex = LICENSE_PLATE_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "license_plate").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "search",
            regex = SEARCH_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "search").register(meterRegistry),
            preserve = false
        ),
        RedactionRule(
            name = "uuid_preserve",
            regex = UUID_PRESERVE_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "uuid_preserve").register(meterRegistry),
            preserve = true
        ),
        RedactionRule(
            name = "url_preserve",
            regex = URL_PRESERVE_REGEX,
            counter = Counter.builder("redactions_total").tag("rule", "url_preserve").register(meterRegistry),
            preserve = true
        )
    )

    private companion object {
        val KEEP_REGEX = Regex("((nav|test)[0-9]{6})")
        val FNR_LOCAL_REGEX = Regex("\\b\\d{6}\\d{5}\\b")
        val FNR_REGEX = Regex("(?<!\\d)\\d{11}(?!\\d)")
        val NAVIDENT_REGEX = Regex("(?<![a-zA-Z0-9])[a-zA-Z]\\d{6}(?!\\d)")
        val EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val IP_REGEX = Regex("(?<!\\d)\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?!\\d)")
        val PHONE_REGEX = Regex("(?<!\\d)[2-9]\\d{7}(?!\\d)")
        val NAME_REGEX = Regex("\\b[A-ZÆØÅ][a-zæøå]{1,20}\\s[A-ZÆØÅ][a-zæøå]{1,20}(?:\\s[A-ZÆØÅ][a-zæøå]{1,20})?\\b")
        val ADDRESS_REGEX = Regex("\\b\\d{4}\\s[A-ZÆØÅ][A-ZÆØÅa-zæøå]+(?:\\s[A-ZÆØÅa-zæøå]+)*\\b")
        val SECRET_ADDRESS_REGEX = Regex("(?i)hemmelig(?:%20|\\s+)(?:20\\s*%(?:%20|\\s+))?adresse")
        val ACCOUNT_REGEX = Regex("(?<!\\d)\\d{4}\\.?\\d{2}\\.?\\d{5}(?!\\d)")
        val ORG_NUMBER_REGEX = Regex("(?<!\\d)\\d{9}(?!\\d)")
        val LICENSE_PLATE_REGEX = Regex("(?<![a-zA-Z])[A-Z]{2}\\s?\\d{5}(?!\\d)")
        val SEARCH_REGEX = Regex("[?&](?:q|query|search|k|ord)=[^&]+")
        val UUID_PRESERVE_REGEX =
            Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
        val URL_PRESERVE_REGEX = Regex(
            pattern = """(?x)
                https?://[A-Za-z0-9._\-]+(?:\.[A-Za-z0-9._\-]+)*(?::[0-9]+)?(?:/[A-Za-z0-9._/%?&=-]*)?
                |
                (?<!@)[A-Za-z0-9._\-]+\.[A-Za-z]{2,}(?:/[A-Za-z0-9._/%?&=-]+)?
            """.trimIndent(),
            options = setOf(RegexOption.COMMENTS)
        )
        val FILEPATH_REGEX = Regex(
            """(?x)
                (?:
                  (?:[A-Za-z]:)?
                  (?:[\\/][A-Za-z0-9._\-\s]+)+
                )
            """.trimIndent()
        )
    }
}
