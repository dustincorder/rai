package com.dustincorder.rai.speech

/**
 * Trusted, allowlisted downloadable packs only. Empty until model-weight
 * redistribution license is verified; never expose dead Local Neural UI.
 */
data class TtsModelCatalogEntry(
    val id: String,
    val displayName: String,
    val version: String,
    val languageTags: Set<String>,
    val downloadUrl: String,
    val archiveFormat: String,
    val sha256: String,
    val byteSize: Long,
    val modelPath: String,
    val tokensPath: String,
    val dataDir: String?,
    val licenseName: String,
    val licenseUrl: String,
    val sourceUrl: String,
    val attribution: String,
)

object TtsModelCatalog {
    /** No entry is safe to ship until model-weight redistribution terms are explicit. */
    val trusted: List<TtsModelCatalogEntry> = emptyList()

    val localNeuralAvailable: Boolean get() = trusted.isNotEmpty()
}
