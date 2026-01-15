package no.nav.reops.filter

internal class Redactor(
    private val rules: List<RedactionRule>
) {
    fun redact(
        input: String,
        excludedLabels: Set<String> = emptySet(),
        preserveUrls: Boolean = true
    ): String {
        var result = input

        // 1) Preserve UUIDs
        val preservedUuids = mutableListOf<String>()
        FilterPatterns.UUID_REGEX.findAll(result).forEachIndexed { i, match ->
            preservedUuids.add(match.value)
            result = result.replace(match.value, "__PRESERVED_UUID_${i}__")
        }

        // 2) Optionally preserve URL-like substrings in free-text
        val preservedUrls = mutableListOf<String>()
        if (preserveUrls) {
            FilterPatterns.URL_REGEX.findAll(result).forEachIndexed { i, match ->
                preservedUrls.add(match.value)
                result = result.replace(match.value, "__PRESERVED_URL_${i}__")
            }
        }

        // 3) Apply rules
        for (rule in rules) {
            if (rule.label in excludedLabels) continue
            if (!rule.regex.containsMatchIn(result)) continue

            rule.counter.increment()

            if (!rule.preserve) {
                result = rule.regex.replace(result) { "[${rule.label}]" }
            }
        }

        // 4) Restore URLs
        preservedUrls.forEachIndexed { i, url ->
            result = result.replace("__PRESERVED_URL_${i}__", url)
        }

        // 5) Restore UUIDs
        preservedUuids.forEachIndexed { i, uuid ->
            result = result.replace("__PRESERVED_UUID_${i}__", uuid)
        }

        return result
    }
}