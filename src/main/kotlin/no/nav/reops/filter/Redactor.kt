package no.nav.reops.filter

internal class Redactor(
    private val rules: List<RedactionRule>
) {
    fun redact(
        input: String, excludedLabels: Set<String> = emptySet(), preserveUrls: Boolean = true
    ): String {
        var result = input

        // 1) Preserve UUIDs
        val preservedUuids = PlaceholderStore(prefix = "__PRESERVED_UUID_", suffix = "__")
        result = preservedUuids.preserveAll(result, FilterPatterns.UUID_REGEX)

        // 2) Optionally preserve URL-like substrings in free-text
        val preservedUrls = PlaceholderStore(prefix = "__PRESERVED_URL_", suffix = "__")
        if (preserveUrls) {
            result = preservedUrls.preserveAll(result, FilterPatterns.URL_REGEX)
        }

        // 3) Apply rules (same order, same checks)
        for (rule in rules) {
            if (rule.label in excludedLabels) continue
            if (!rule.regex.containsMatchIn(result)) continue

            rule.counter.increment()

            if (!rule.preserve) {
                result = rule.regex.replace(result) { "[${rule.label}]" }
            }
        }

        // 4) Restore URLs
        result = preservedUrls.restoreAll(result)

        // 5) Restore UUIDs
        result = preservedUuids.restoreAll(result)

        return result
    }

    private class PlaceholderStore(
        private val prefix: String, private val suffix: String
    ) {
        private val values = mutableListOf<String>()
        private val restoreRegex by lazy {
            Regex(Regex.escape(prefix) + "(\\d+)" + Regex.escape(suffix))
        }

        fun preserveAll(input: String, regex: Regex): String {
            val matches = regex.findAll(input).toList()
            if (matches.isEmpty()) return input

            val sb = StringBuilder(input.length)
            var lastEnd = 0
            for (match in matches) {
                sb.append(input, lastEnd, match.range.first)
                val idx = values.size
                values.add(match.value)
                sb.append(prefix).append(idx).append(suffix)
                lastEnd = match.range.last + 1
            }
            sb.append(input, lastEnd, input.length)
            return sb.toString()
        }

        fun restoreAll(input: String): String {
            if (values.isEmpty()) return input
            return restoreRegex.replace(input) { mr ->
                val idx = mr.groupValues[1].toInt()
                if (idx in values.indices) values[idx] else mr.value
            }
        }
    }
}