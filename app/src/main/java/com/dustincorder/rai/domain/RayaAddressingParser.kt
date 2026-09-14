package com.dustincorder.rai.domain

data class RayaAddressingResult(
    val addressed: Boolean,
    val query: String,
)

object RayaAddressingParser {
    private const val NAME = "(?:райя|рая|raya)"
    private const val SEPARATOR = "[\\s,.!?;:—-]"

    private val prefix = Regex(
        "^(?:$NAME(?=$SEPARATOR|$)${SEPARATOR}*)+",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): RayaAddressingResult {
        val trimmed = text.trim()
        val match = prefix.find(trimmed) ?: return RayaAddressingResult(false, trimmed)
        return RayaAddressingResult(true, trimmed.removeRange(match.range).trim())
    }
}
