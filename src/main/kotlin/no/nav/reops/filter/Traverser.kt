package no.nav.reops.filter

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

internal class Traverser(
    private val keyPolicy: KeyPolicy,
    private val urlPolicy: UrlPolicy,
    private val redactor: Redactor,
    private val excludeFilters: Set<String>
) {
    fun transform(root: JsonNode): JsonNode =
        transformNode(root, NodeContext(depth = 0, containerKey = null, key = null))

    private fun transformNode(node: JsonNode, ctx: NodeContext): JsonNode {
        return when {
            node.isNull -> node

            node.isString -> {
                val raw = node.asString()
                val out = if (urlPolicy.isUrlField(ctx)) {
                    urlPolicy.redactUrl(raw, redactor)
                } else {
                    redactor.redact(input = raw, excludedLabels = emptySet(), preserveUrls = true)
                }
                JsonNodeFactory.instance.textNode(out)
            }

            node.isNumber -> {
                // Only redact “integral-looking” numbers; otherwise keep as-is (to avoid breaking decimals).
                val plain = node.numberValue()?.toPlainIntegralStringOrNull()
                if (plain == null) node
                else {
                    val redacted = redactor.redact(plain)
                    if (redacted == plain) node else JsonNodeFactory.instance.textNode(redacted)
                }
            }

            node.isBoolean -> {
                val raw = node.asBoolean().toString()
                val redacted = redactor.redact(raw)
                if (redacted == raw) node else JsonNodeFactory.instance.textNode(redacted)
            }

            node.isObject -> transformObject(node as ObjectNode, ctx)

            node.isArray -> transformArray(node as ArrayNode, ctx)

            else -> node
        }
    }

    private fun transformObject(obj: ObjectNode, ctx: NodeContext): JsonNode {
        val out = JsonNodeFactory.instance.objectNode()

        // Iterate entries (your relocated Jackson supports properties() as we used on producer side)
        for ((key, value) in obj.properties()) {
            // If key matches excludeFilters, keep subtree untouched.
            if (key in excludeFilters) {
                out.set(key, value)
                continue
            }

            when (val decision = keyPolicy.decide(key, value)) {
                is KeyPolicy.Decision.Drop -> {
                    // omit key
                }
                is KeyPolicy.Decision.Preserve -> {
                    out.set(key, decision.value as? JsonNode ?: value)
                }
                is KeyPolicy.Decision.Replace -> {
                    out.set(key, toJsonNode(decision.value))
                }
                is KeyPolicy.Decision.None -> {
                    val childCtx = NodeContext(
                        depth = ctx.depth + 1,
                        containerKey = ctx.containerKey,
                        key = key
                    )

                    val nextCtx =
                        if (value.isObject) NodeContext(depth = ctx.depth + 1, containerKey = key, key = null)
                        else childCtx

                    out.set(key, transformNode(value, nextCtx))
                }
            }
        }

        return out
    }

    private fun transformArray(arr: ArrayNode, ctx: NodeContext): JsonNode {
        val out = JsonNodeFactory.instance.arrayNode()
        var idx = 0
        for (child in arr) {
            out.add(transformNode(child, ctx.copy(depth = ctx.depth + 1, containerKey = null, key = null)))
            idx++
        }
        return out
    }

    private fun toJsonNode(value: Any?): JsonNode {
        val f = JsonNodeFactory.instance
        return when (value) {
            null -> f.nullNode()
            is JsonNode -> value
            is String -> f.textNode(value)
            is Boolean -> f.booleanNode(value)
            is Int -> f.numberNode(value)
            is Long -> f.numberNode(value)
            is Double -> f.numberNode(value)
            is Float -> f.numberNode(value)
            is java.math.BigInteger -> f.numberNode(value)
            is java.math.BigDecimal -> f.numberNode(value)
            else -> f.textNode(value.toString())
        }
    }

    private fun Number.toPlainIntegralStringOrNull(): String? = when (this) {
        is Byte, is Short, is Int, is Long -> this.toLong().toString()
        is java.math.BigInteger -> this.toString()
        is java.math.BigDecimal -> if (this.stripTrailingZeros().scale() <= 0) this.toBigIntegerExact().toString() else null
        else -> null
    }
}