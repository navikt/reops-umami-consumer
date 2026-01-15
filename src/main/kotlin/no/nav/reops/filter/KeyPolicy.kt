package no.nav.reops.filter

internal class KeyPolicy(
    private val preservedKeys: Set<String>,
    private val droppedKeys: Set<String>,
    private val forcedValues: Map<String, String>,
    private val advertisingIdKeys: Set<String>
) {
    sealed class Decision {
        data object Drop : Decision()
        data class Replace(val value: Any?) : Decision()
        data class Preserve(val value: Any?) : Decision()
        data object None : Decision()
    }

    fun decide(key: String, value: Any?): Decision {
        if (key in droppedKeys) return Decision.Drop
        if (key in preservedKeys) return Decision.Preserve(value)
        forcedValues[key]?.let { return Decision.Replace(it) }
        if (key in advertisingIdKeys) return Decision.Replace("[PROXY]")
        return Decision.None
    }
}