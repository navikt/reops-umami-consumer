package no.nav.reops.filter

internal class Redactor(
    private val rules: List<RedactionRule>
) {
    fun redact(
        input: String, excludedLabels: Set<String> = emptySet(), preserveUrls: Boolean = true
    ): String {
        var result = input

        // 1) Preserve UUIDs — replace all matches in a single pass using regex.replace
        result = FilterPatterns.UUID_REGEX.replaceIndexed(result, UUID_PLACEHOLDER)

        // 2) Optionally preserve URL-like substrings in free-text
        if (preserveUrls) {
            result = FilterPatterns.URL_REGEX.replaceIndexed(result, URL_PLACEHOLDER)
        }

        // 3) Apply rules
        for (rule in rules) {
            if (rule.label in excludedLabels) continue

            if (rule.preserve) {
                if (rule.regex.containsMatchIn(result)) {
                    rule.counter.increment()
                }
            } else {
                val replacement = "[${rule.label}]"
                val replaced = rule.regex.replace(result, replacement)
                if (replaced !== result) {  // identity check — replace returns same instance when no match
                    rule.counter.increment()
                    result = replaced
                }
            }
        }

        // 4) Restore URLs
        if (preserveUrls) {
            result = URL_PLACEHOLDER.restoreAll(result)
        }

        // 5) Restore UUIDs
        result = UUID_PLACEHOLDER.restoreAll(result)

        return result
    }

    private companion object {
        private val UUID_PLACEHOLDER = PlaceholderPattern(prefix = "__PRESERVED_UUID_", suffix = "__")
        private val URL_PLACEHOLDER = PlaceholderPattern(prefix = "__PRESERVED_URL_", suffix = "__")
    }
}

/**
 * Replaces all matches of [this] regex in [input] with indexed placeholders,
 * storing original values in a thread-local list. Returns the substituted string.
 */
private fun Regex.replaceIndexed(input: String, pattern: PlaceholderPattern): String {
    val originals = pattern.originals.get()
    originals.clear()
    var index = 0
    return replace(input) { match ->
        originals.add(match.value)
        pattern.placeholder(index++)
    }
}

/**
 * Holds the prefix/suffix for placeholder tokens and the per-thread captured originals.
 * Using ThreadLocal avoids allocating a new list per call while remaining safe for
 * the concurrent Kafka-listener threads.
 */
internal class PlaceholderPattern(
    private val prefix: String, private val suffix: String
) {
    val originals: ThreadLocal<MutableList<String>> = ThreadLocal.withInitial { ArrayList(8) }

    fun placeholder(i: Int): String = "$prefix${i}$suffix"

    fun restoreAll(input: String): String {
        val values = originals.get()
        if (values.isEmpty()) return input

        val sb = StringBuilder(input.length)
        var pos = 0
        // Single-pass restore: scan for prefix, find suffix, look up index
        while (pos < input.length) {
            val start = input.indexOf(prefix, pos)
            if (start < 0) {
                sb.append(input, pos, input.length)
                break
            }
            sb.append(input, pos, start)
            val numStart = start + prefix.length
            val end = input.indexOf(suffix, numStart)
            if (end < 0) {
                // Malformed placeholder — just append the rest
                sb.append(input, start, input.length)
                break
            }
            val idx = input.substring(numStart, end).toIntOrNull()
            if (idx != null && idx in values.indices) {
                sb.append(values[idx])
            } else {
                // Unknown placeholder — keep as-is
                sb.append(input, start, end + suffix.length)
            }
            pos = end + suffix.length
        }
        values.clear()
        return sb.toString()
    }
}