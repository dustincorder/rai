package com.dustincorder.rai.domain

data class RayaAddressingResult(
    val addressed: Boolean,
    val query: String,
)

object RayaAddressingParser {
    private val prefix = Regex("^(?:райя|raya)(?=\\s|[,.!?;:—-]|$)[\\s,.!?;:—-]*", RegexOption.IGNORE_CASE)

    fun parse(text: String): RayaAddressingResult {
        val match = prefix.find(text.trim()) ?: return RayaAddressingResult(false, text.trim())
        return RayaAddressingResult(true, text.trim().removeRange(match.range).trim())
    }
}
