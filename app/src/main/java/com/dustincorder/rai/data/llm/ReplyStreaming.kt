package com.dustincorder.rai.data.llm

import com.dustincorder.rai.domain.ReplyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private val TEXT_KEY = Regex(""""text"\s*:\s*"""")

/**
 * Best-effort prefix of the visible answer text inside a partially streamed
 * structured envelope. Only fully decoded characters are returned, so the UI
 * never shows raw JSON fragments, partial escapes, or metadata.
 */
fun extractVisibleTextPrefix(raw: String): String {
    val candidate = stripMarkdownFence(raw.trim())
    for (match in TEXT_KEY.findAll(candidate)) {
        if (!isKeyPosition(candidate, match.range.first)) continue
        return scanJsonString(candidate, match.range.last + 1)
    }
    return ""
}

private fun isKeyPosition(candidate: String, quoteIndex: Int): Boolean {
    var i = quoteIndex - 1
    while (i >= 0 && candidate[i].isWhitespace()) i--
    return i < 0 || candidate[i] == '{' || candidate[i] == ','
}

private fun scanJsonString(candidate: String, start: Int): String {
    val out = StringBuilder()
    var i = start
    while (i < candidate.length) {
        when (val c = candidate[i]) {
            '"' -> return out.toString()
            '\\' -> {
                if (i + 1 >= candidate.length) return out.toString()
                when (val e = candidate[i + 1]) {
                    '"', '\\', '/' -> out.append(e)
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (i + 5 >= candidate.length) return out.toString()
                        val code = candidate.substring(i + 2, i + 6).toIntOrNull(16)
                            ?: return out.toString()
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    else -> return out.toString()
                }
                i += 2
            }
            '\n', '\r' -> return out.toString()
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/**
 * Converts a raw chunk stream into [ReplyEvent]s: incremental visible-text deltas
 * followed by exactly one validated [ReplyEvent.Completed]. Malformed final
 * metadata fails via [parseRayaResponse], never as visible text.
 */
fun Flow<String>.toReplyEvents(json: Json): Flow<ReplyEvent> = flow {
    val raw = StringBuilder()
    var emitted = 0
    collect { chunk ->
        raw.append(chunk)
        val prefix = extractVisibleTextPrefix(raw.toString())
        if (prefix.length > emitted) {
            emit(ReplyEvent.TextDelta(prefix.substring(emitted)))
            emitted = prefix.length
        }
    }
    emit(ReplyEvent.Completed(parseRayaResponse(raw.toString(), json)))
}

/** Server-sent-events payload of a line, or null for comments/blank lines. */
internal fun parseSseData(line: String): String? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
    if (!trimmed.startsWith("data:")) return null
    return trimmed.substringAfter("data:").trim()
}

internal fun providerErrorMessage(body: String, json: Json): String? {
    if (body.isBlank()) return null
    val message = runCatching {
        json.decodeFromString<ProviderErrorEnvelope>(body).error?.message
    }.getOrNull()
    return LlmErrorClassifier.sanitizeProviderMessage(message ?: return null)
}

/**
 * POSTs [request] and streams SSE `data:` payloads. A plain JSON body (server
 * ignoring `stream=true`) is emitted whole as a single chunk.
 */
internal fun OkHttpClient.streamPostLines(request: Request, json: Json): Flow<String> = flow {
    val call = newCall(request)
    try {
        val response = withContext(Dispatchers.IO) { call.execute() }
        if (!response.isSuccessful) {
            val body = runCatching {
                withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
            }.getOrDefault("")
            val code = response.code
            response.close()
            throw LlmHttpException(code, providerErrorMessage(body, json))
        }
        val source = response.body!!.source()
        try {
            val first = readLine(source)
            if (first != null && first.trimStart().startsWith("{") && parseSseData(first) == null) {
                val rest = withContext(Dispatchers.IO) { source.readUtf8() }
                emit(first + rest)
                return@flow
            }
            if (first != null) emitSsePayload(first)?.let { emit(it) }
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = readLine(source) ?: break
                emitSsePayload(line)?.let { emit(it) }
            }
        } finally {
            response.close()
        }
    } finally {
        call.cancel()
    }
}

private suspend fun readLine(source: okio.BufferedSource): String? {
    return try {
        withContext(Dispatchers.IO) { source.readUtf8Line() }
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        throw error
    }
}

/** Returns the SSE payload, or null for comments, blanks and the DONE marker. */
private fun emitSsePayload(line: String): String? {
    val payload = parseSseData(line) ?: return null
    return payload.takeUnless { it == "[DONE]" }
}
