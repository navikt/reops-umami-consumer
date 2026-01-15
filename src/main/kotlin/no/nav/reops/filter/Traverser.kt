package no.nav.reops.filter

import java.math.BigDecimal
import java.math.BigInteger

internal class Traverser(
    private val keyPolicy: KeyPolicy,
    private val urlPolicy: UrlPolicy,
    private val redactor: Redactor,
    private val excludeFilters: Set<String>
) {
    fun transform(root: Any?): Any? =
        transformAny(root, NodeContext(depth = 0, containerKey = null, key = null))

    private fun transformAny(value: Any?, ctx: NodeContext): Any? = when (value) {
        null -> null

        is String -> {
            if (urlPolicy.isUrlField(ctx)) {
                urlPolicy.redactUrl(value, redactor)
            } else {
                redactor.redact(input = value, excludedLabels = emptySet(), preserveUrls = true)
            }
        }

        is Number -> redactNumber(value)
        is Boolean -> keepTypeIfUnchanged(value) { redactor.redact(it.toString()) }

        is Map<*, *> -> transformMap(value, ctx)

        is List<*> -> value.map { transformAny(it, ctx.copy(depth = ctx.depth + 1, containerKey = null, key = null)) }
        is Set<*> -> value.map { transformAny(it, ctx.copy(depth = ctx.depth + 1, containerKey = null, key = null)) }.toSet()
        is Array<*> -> value.map { transformAny(it, ctx.copy(depth = ctx.depth + 1, containerKey = null, key = null)) }.toTypedArray()

        else -> value
    }

    private fun redactNumber(value: Number): Any {
        val plain = value.toPlainIntegralStringOrNull() ?: return value
        val redacted = redactor.redact(plain)
        return if (redacted == plain) value else redacted
    }

    private fun Number.toPlainIntegralStringOrNull(): String? = when (this) {
        is Byte, is Short, is Int, is Long -> this.toLong().toString()
        is BigInteger -> this.toString()
        is BigDecimal -> if (this.stripTrailingZeros().scale() <= 0) this.toBigIntegerExact().toString() else null
        else -> null
    }

    private fun transformMap(map: Map<*, *>, ctx: NodeContext): Map<String, Any?> {
        return map.entries.asSequence().mapNotNull { (k, v) ->
            val key = k?.toString() ?: return@mapNotNull null

            // NEW: excludeFilters short-circuit
            // If key matches, do not redact and do not traverse below this key.
            if (key in excludeFilters) {
                return@mapNotNull key to v
            }

            when (val decision = keyPolicy.decide(key, v)) {
                is KeyPolicy.Decision.Drop -> null
                is KeyPolicy.Decision.Preserve -> key to decision.value
                is KeyPolicy.Decision.Replace -> key to decision.value
                is KeyPolicy.Decision.None -> {
                    val valueCtx = NodeContext(
                        depth = ctx.depth + 1,
                        containerKey = ctx.containerKey,
                        key = key
                    )

                    val outV = when (v) {
                        is String -> transformAny(v, valueCtx)
                        is Map<*, *> -> transformAny(v, NodeContext(depth = ctx.depth + 1, containerKey = key, key = null))
                        else -> transformAny(v, NodeContext(depth = ctx.depth + 1, containerKey = null, key = null))
                    }

                    key to outV
                }
            }
        }.toMap()
    }

    private inline fun <T : Any> keepTypeIfUnchanged(value: T, redactFn: (T) -> String): Any {
        val raw = value.toString()
        val redacted = redactFn(value)
        return if (redacted == raw) value else redacted
    }
}