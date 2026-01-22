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
                val out = when {
                    urlPolicy.isUrlField(ctx) -> urlPolicy.redactUrl(raw, redactor, excludePathLabels = true)
                    urlPolicy.isUrlLikeField(ctx) -> urlPolicy.redactUrl(raw, redactor, excludePathLabels = true)
                    else -> redactor.redact(input = raw, excludedLabels = emptySet(), preserveUrls = true)
                }
                JsonNodeFactory.instance.stringNode(out)
            }

            node.isNumber -> node
            node.isBoolean -> node
            node.isObject -> transformObject(node as ObjectNode, ctx)
            node.isArray -> transformArray(node as ArrayNode, ctx)
            else -> node
        }
    }

    private fun transformObject(obj: ObjectNode, ctx: NodeContext): JsonNode {
        val out = JsonNodeFactory.instance.objectNode()
        for ((key, value) in obj.properties()) {
            if (key in excludeFilters) {
                out.set(key, value)
                continue
            }

            when (val decision = keyPolicy.decide(key, value)) {
                is KeyPolicy.Decision.Drop -> {
                    // ignore
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
        for (child in arr) {
            out.add(transformNode(child, ctx.copy(depth = ctx.depth + 1, containerKey = null, key = null)))
        }
        return out
    }

    private fun toJsonNode(value: Any?): JsonNode {
        val f = JsonNodeFactory.instance
        return when (value) {
            null -> f.nullNode()
            is JsonNode -> value
            is String -> f.stringNode(value)
            is Boolean -> f.booleanNode(value)
            is Int -> f.numberNode(value)
            is Long -> f.numberNode(value)
            is Double -> f.numberNode(value)
            is Float -> f.numberNode(value)
            is java.math.BigInteger -> f.numberNode(value)
            is java.math.BigDecimal -> f.numberNode(value)
            else -> f.stringNode(value.toString())
        }
    }
}