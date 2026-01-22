package no.nav.reops.filter

internal data class NodeContext(
    val depth: Int, val containerKey: String?, val key: String?
)

internal class UrlPolicy(
    private val isNestedUrlField: (NodeContext) -> Boolean,
    private val urlLikeKeys: Set<String>,
    private val pathExcludedLabels: Set<String>
) {
    fun isUrlField(ctx: NodeContext): Boolean = isNestedUrlField(ctx)

    fun isUrlLikeField(ctx: NodeContext): Boolean = ctx.key != null && ctx.key in urlLikeKeys

    fun redactUrl(url: String, redactor: Redactor, excludePathLabels: Boolean = true): String {
        val pathLabels = if (excludePathLabels) pathExcludedLabels else emptySet()
        val q = url.indexOf('?')
        if (q < 0) {
            return redactor.redact(
                input = url, excludedLabels = pathLabels, preserveUrls = false
            )
        }

        val path = url.substring(0, q)
        val query = url.substring(q)

        val redactedPath = redactor.redact(
            input = path, excludedLabels = pathLabels, preserveUrls = false
        )

        val redactedQuery = redactor.redact(
            input = query, excludedLabels = emptySet(), preserveUrls = false
        )

        return redactedPath + redactedQuery
    }
}