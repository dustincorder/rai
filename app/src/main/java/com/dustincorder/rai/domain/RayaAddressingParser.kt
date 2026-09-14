package com.dustincorder.rai.domain

import java.text.Normalizer

data class RayaAddressingResult(
    val addressed: Boolean,
    val query: String,
)

object RayaAddressingParser {
    private const val NAMES = "(?:райя|рая|raya)"
    private const val SEP = "[\\s\\p{P}\\p{Z}\\p{Cf}]"
    private const val BOUNDARY = "(?![\\p{L}\\p{N}])"

    private val prefix = Regex(
        "^${SEP}*${NAMES}${BOUNDARY}(?:${SEP}+${NAMES}${BOUNDARY})*${SEP}*",
        RegexOption.IGNORE_CASE,
    )

    fun parse(raw: String): RayaAddressingResult {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val text = normalized.trim { it.isWhitespace() }
        val match = prefix.find(text) ?: return RayaAddressingResult(false, text)
        return RayaAddressingResult(true, text.removeRange(match.range).trim { it.isWhitespace() })
    }
}
