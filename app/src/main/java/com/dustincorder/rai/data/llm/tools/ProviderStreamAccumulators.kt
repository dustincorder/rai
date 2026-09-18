package com.dustincorder.rai.data.llm.tools

import com.dustincorder.rai.data.llm.extractVisibleTextPrefix
import com.dustincorder.rai.data.llm.parseRayaResponse
import com.dustincorder.rai.domain.tools.ModelRoundStreamEvent
import com.dustincorder.rai.domain.tools.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Extracts visible text for user presentation from partially streamed output,
 * stripping JSON envelope if structured or returning direct text if plain.
 */
fun extractVisibleText(raw: String): String {
    val trimmed = raw.trimStart()
    if (trimmed.startsWith("{") || trimmed.startsWith("```")) {
        return extractVisibleTextPrefix(raw)
    }
    return raw
}

class OpenAiStreamAccumulator(private val json: Json) {
    private val rawContent = StringBuilder()
    private var emittedTextLength = 0
    private val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()

    data class ToolCallBuilder(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    fun onChunk(chunkJson: String): List<ModelRoundStreamEvent> {
        val events = mutableListOf<ModelRoundStreamEvent>()
        val parsed = runCatching { json.parseToJsonElement(chunkJson).jsonObject }.getOrNull() ?: return events
        val choice = parsed["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return events
        val delta = choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject ?: return events

        delta["content"]?.let { contentEl ->
            val textFragment = runCatching { contentEl.jsonPrimitive.content }.getOrNull()
            if (!textFragment.isNullOrEmpty()) {
                rawContent.append(textFragment)
                val visible = extractVisibleText(rawContent.toString())
                if (visible.length > emittedTextLength) {
                    val deltaText = visible.substring(emittedTextLength)
                    emittedTextLength = visible.length
                    events.add(ModelRoundStreamEvent.TextDelta(deltaText))
                }
            }
        }

        delta["tool_calls"]?.let { tcEl ->
            val array = runCatching { tcEl.jsonArray }.getOrNull() ?: return@let
            for (item in array) {
                val obj = runCatching { item.jsonObject }.getOrNull() ?: continue
                val index = obj["index"]?.jsonPrimitive?.intOrNull ?: toolCallBuilders.size
                val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }
                obj["id"]?.jsonPrimitive?.content?.let { if (it.isNotEmpty()) builder.id = it }
                val functionObj = obj["function"]?.jsonObject
                functionObj?.get("name")?.jsonPrimitive?.content?.let { if (it.isNotEmpty()) builder.name = it }
                functionObj?.get("arguments")?.jsonPrimitive?.content?.let { builder.arguments.append(it) }
            }
        }

        return events
    }

    fun onFinish(): ModelRoundStreamEvent {
        if (toolCallBuilders.isNotEmpty()) {
            val calls = toolCallBuilders.entries.sortedBy { it.key }.map { (_, b) ->
                val argsObj = runCatching {
                    json.parseToJsonElement(b.arguments.toString().ifBlank { "{}" }).jsonObject
                }.getOrElse { buildJsonObject {} }
                ToolCall(b.id.ifBlank { "call_${UUID.randomUUID()}" }, b.name, argsObj)
            }
            return ModelRoundStreamEvent.ToolCalls(calls)
        }
        val rayaResponse = parseRayaResponse(rawContent.toString(), json)
        return ModelRoundStreamEvent.Completed(rayaResponse)
    }
}

class AnthropicStreamAccumulator(private val json: Json) {
    private val rawContent = StringBuilder()
    private var emittedTextLength = 0
    private val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()

    data class ToolCallBuilder(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    fun onChunk(chunkJson: String): List<ModelRoundStreamEvent> {
        val events = mutableListOf<ModelRoundStreamEvent>()
        val parsed = runCatching { json.parseToJsonElement(chunkJson).jsonObject }.getOrNull() ?: return events
        val type = parsed["type"]?.jsonPrimitive?.content

        when (type) {
            "content_block_start" -> {
                val index = parsed["index"]?.jsonPrimitive?.intOrNull ?: 0
                val block = parsed["content_block"]?.jsonObject
                if (block?.get("type")?.jsonPrimitive?.content == "tool_use") {
                    val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }
                    builder.id = block["id"]?.jsonPrimitive?.content.orEmpty()
                    builder.name = block["name"]?.jsonPrimitive?.content.orEmpty()
                }
            }
            "content_block_delta" -> {
                val index = parsed["index"]?.jsonPrimitive?.intOrNull ?: 0
                val delta = parsed["delta"]?.jsonObject
                val deltaType = delta?.get("type")?.jsonPrimitive?.content
                if (deltaType == "text_delta") {
                    val text = delta["text"]?.jsonPrimitive?.content.orEmpty()
                    if (text.isNotEmpty()) {
                        rawContent.append(text)
                        val visible = extractVisibleText(rawContent.toString())
                        if (visible.length > emittedTextLength) {
                            val deltaText = visible.substring(emittedTextLength)
                            emittedTextLength = visible.length
                            events.add(ModelRoundStreamEvent.TextDelta(deltaText))
                        }
                    }
                } else if (deltaType == "input_json_delta") {
                    val partialJson = delta["partial_json"]?.jsonPrimitive?.content.orEmpty()
                    val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }
                    builder.arguments.append(partialJson)
                }
            }
            // Non-streaming fallback if whole message returned
            null -> {
                val contentArray = parsed["content"]?.jsonArray
                if (contentArray != null) {
                    for (item in contentArray) {
                        val obj = item.jsonObject
                        if (obj["type"]?.jsonPrimitive?.content == "tool_use") {
                            val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
                            val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                            val input = obj["input"]?.jsonObject ?: buildJsonObject {}
                            toolCallBuilders[toolCallBuilders.size] = ToolCallBuilder(id, name, StringBuilder(input.toString()))
                        } else if (obj["type"]?.jsonPrimitive?.content == "text") {
                            val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
                            rawContent.append(text)
                        }
                    }
                }
            }
        }
        return events
    }

    fun onFinish(): ModelRoundStreamEvent {
        if (toolCallBuilders.isNotEmpty()) {
            val calls = toolCallBuilders.entries.sortedBy { it.key }.map { (_, b) ->
                val argsObj = runCatching {
                    json.parseToJsonElement(b.arguments.toString().ifBlank { "{}" }).jsonObject
                }.getOrElse { buildJsonObject {} }
                ToolCall(b.id.ifBlank { "call_${UUID.randomUUID()}" }, b.name, argsObj)
            }
            return ModelRoundStreamEvent.ToolCalls(calls)
        }
        val rayaResponse = parseRayaResponse(rawContent.toString(), json)
        return ModelRoundStreamEvent.Completed(rayaResponse)
    }
}

class GeminiStreamAccumulator(private val json: Json) {
    private val rawContent = StringBuilder()
    private var emittedTextLength = 0
    private val toolCalls = mutableListOf<ToolCall>()

    fun onChunk(chunkJson: String): List<ModelRoundStreamEvent> {
        val events = mutableListOf<ModelRoundStreamEvent>()
        val parsed = runCatching { json.parseToJsonElement(chunkJson).jsonObject }.getOrNull() ?: return events
        val candidates = parsed["candidates"]?.jsonArray
        val parts = candidates?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: return events

        for (part in parts) {
            val partObj = part.jsonObject
            val text = partObj["text"]?.jsonPrimitive?.content
            if (!text.isNullOrEmpty()) {
                rawContent.append(text)
                val visible = extractVisibleText(rawContent.toString())
                if (visible.length > emittedTextLength) {
                    val deltaText = visible.substring(emittedTextLength)
                    emittedTextLength = visible.length
                    events.add(ModelRoundStreamEvent.TextDelta(deltaText))
                }
            }
            val functionCall = partObj["functionCall"]?.jsonObject
            if (functionCall != null) {
                val name = functionCall["name"]?.jsonPrimitive?.content.orEmpty()
                val args = functionCall["args"]?.jsonObject ?: buildJsonObject {}
                val callId = "gemini_call_${name}_${toolCalls.size}"
                toolCalls.add(ToolCall(callId, name, args))
            }
        }
        return events
    }

    fun onFinish(): ModelRoundStreamEvent {
        if (toolCalls.isNotEmpty()) {
            return ModelRoundStreamEvent.ToolCalls(toolCalls)
        }
        val rayaResponse = parseRayaResponse(rawContent.toString(), json)
        return ModelRoundStreamEvent.Completed(rayaResponse)
    }
}

fun OpenAiStreamAccumulator.accumulate(chunks: Flow<String>): Flow<ModelRoundStreamEvent> = flow {
    chunks.collect { chunk ->
        onChunk(chunk).forEach { emit(it) }
    }
    emit(onFinish())
}

fun AnthropicStreamAccumulator.accumulate(chunks: Flow<String>): Flow<ModelRoundStreamEvent> = flow {
    chunks.collect { chunk ->
        onChunk(chunk).forEach { emit(it) }
    }
    emit(onFinish())
}

fun GeminiStreamAccumulator.accumulate(chunks: Flow<String>): Flow<ModelRoundStreamEvent> = flow {
    chunks.collect { chunk ->
        onChunk(chunk).forEach { emit(it) }
    }
    emit(onFinish())
}
