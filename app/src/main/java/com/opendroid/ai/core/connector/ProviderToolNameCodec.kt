package com.opendroid.ai.core.connector

/** Encodes registry-qualified names for provider APIs that reject dots in function names. */
internal class ProviderToolNameMapping private constructor(
    private val providerToCanonical: Map<String, String>,
    private val canonicalToProvider: Map<String, String>,
) {
    fun providerName(canonicalName: String): String? = canonicalToProvider[canonicalName]
    fun canonicalName(providerName: String): String? = providerToCanonical[providerName]
    fun entries(): Map<String, String> = canonicalToProvider.toMap()

    companion object {
        /** OpenAI-compatible tool identifiers must be 1–64 ASCII letters, digits, '_' or '-'. */
        const val MODEL_NAME_PATTERN = "^[a-zA-Z0-9_-]{1,64}$"
        private val modelNameRegex = Regex(MODEL_NAME_PATTERN)

        fun fromCanonicalNames(names: Iterable<String>): ProviderToolNameMapping {
            val forward = linkedMapOf<String, String>()
            val reverse = linkedMapOf<String, String>()
            names.forEach { canonical ->
                require(canonical.isNotBlank()) { "Canonical tool name must not be blank." }
                val encoded = canonical.replace(".", "__")
                require(modelNameRegex.matches(encoded)) {
                    "Tool name cannot be represented as a provider function name: $canonical"
                }
                require(encoded !in reverse) {
                    "Tool-name encoding collision: ${reverse[encoded]} and $canonical both map to $encoded."
                }
                require(canonical !in forward) { "Canonical tool names must be unique: $canonical" }
                reverse[encoded] = canonical
                forward[canonical] = encoded
            }
            return ProviderToolNameMapping(reverse, forward)
        }
    }
}
