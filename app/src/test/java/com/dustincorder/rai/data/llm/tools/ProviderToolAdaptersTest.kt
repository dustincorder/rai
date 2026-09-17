package com.dustincorder.rai.data.llm.tools

import com.dustincorder.rai.domain.tools.ExecutionKind
import com.dustincorder.rai.domain.tools.ModelRoundStep
import com.dustincorder.rai.domain.tools.ProviderSchemaProjection
import com.dustincorder.rai.domain.tools.ToolCall
import com.dustincorder.rai.domain.tools.ToolDefinition
import com.dustincorder.rai.domain.tools.ToolEffect
import com.dustincorder.rai.domain.tools.ToolId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderToolAdaptersTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val openAiAdapter = OpenAiToolAdapter(json)
    private val anthropicAdapter = AnthropicToolAdapter(json)
    private val geminiAdapter = GeminiToolAdapter(json)

    private val sampleSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject { put("type", "string") })
        })
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
    }

    private val sampleTool = ToolDefinition(
        id = ToolId("test_search"),
        name = "search_tool",
        description = "Performs a test search query",
        effect = ToolEffect.ReadOnly,
        executionKind = ExecutionKind.LocalApi,
        inputSchema = sampleSchema,
    )

    @Test
    fun `openAi adapter formats tools payload correctly`() {
        val toolsPayload = openAiAdapter.formatToolsPayload(listOf(sampleTool))
        assertEquals(1, toolsPayload.size)
        val toolObj = toolsPayload[0].jsonObject
        assertEquals("function", toolObj["type"]?.jsonPrimitive?.content)
        val func = toolObj["function"]?.jsonObject
        assertNotNull(func)
        assertEquals("search_tool", func?.get("name")?.jsonPrimitive?.content)
        assertEquals(sampleSchema, func?.get("parameters")?.jsonObject)
    }

    @Test
    fun `openAi adapter parses tool calls from message`() {
        val messageObject = buildJsonObject {
            put("role", "assistant")
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", "call_123")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "search_tool")
                        put("arguments", "{\"query\":\"Kotlin coroutines\"}")
                    })
                })
            })
        }

        val parsedCalls = openAiAdapter.parseToolCalls(messageObject)
        assertEquals(1, parsedCalls.size)
        val call = parsedCalls[0]
        assertEquals("call_123", call.callId)
        assertEquals("search_tool", call.toolName)
        assertEquals("Kotlin coroutines", call.arguments["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun `openAi adapter formats step messages with call and result correlation`() {
        val call = ToolCall("call_abc", "search_tool", buildJsonObject { put("query", "test") })
        val resultData = buildJsonObject { put("answer", "ok") }
        val steps = listOf(
            ModelRoundStep.AssistantToolCalls(listOf(call)),
            ModelRoundStep.ToolExecutionFeedback("call_abc", "search_tool", resultData),
        )

        val messages = openAiAdapter.formatStepMessages(steps)
        assertEquals(2, messages.size)

        // First message: assistant tool_calls
        val assistantMsg = messages[0]
        assertEquals("assistant", assistantMsg["role"]?.jsonPrimitive?.content)
        val toolCallsArray = assistantMsg["tool_calls"]?.jsonArray
        assertNotNull(toolCallsArray)
        assertEquals("call_abc", toolCallsArray?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content)

        // Second message: role=tool with tool_call_id
        val toolMsg = messages[1]
        assertEquals("tool", toolMsg["role"]?.jsonPrimitive?.content)
        assertEquals("call_abc", toolMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals(resultData.toString(), toolMsg["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `anthropic adapter formats tools payload correctly`() {
        val toolsPayload = anthropicAdapter.formatToolsPayload(listOf(sampleTool))
        assertEquals(1, toolsPayload.size)
        val toolObj = toolsPayload[0].jsonObject
        assertEquals("search_tool", toolObj["name"]?.jsonPrimitive?.content)
        assertEquals(sampleSchema, toolObj["input_schema"]?.jsonObject)
    }

    @Test
    fun `anthropic adapter parses tool calls from content array`() {
        val contentArray = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "Let me search that for you...")
            })
            add(buildJsonObject {
                put("type", "tool_use")
                put("id", "toolu_456")
                put("name", "search_tool")
                put("input", buildJsonObject { put("query", "Android 15") })
            })
        }

        val parsedCalls = anthropicAdapter.parseToolCalls(contentArray)
        assertEquals(1, parsedCalls.size)
        val call = parsedCalls[0]
        assertEquals("toolu_456", call.callId)
        assertEquals("search_tool", call.toolName)
        assertEquals("Android 15", call.arguments["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun `anthropic adapter formats step messages into tool_use and tool_result`() {
        val call = ToolCall("toolu_789", "search_tool", buildJsonObject { put("query", "test") })
        val resultData = buildJsonObject { put("found", true) }
        val steps = listOf(
            ModelRoundStep.AssistantToolCalls(listOf(call)),
            ModelRoundStep.ToolExecutionFeedback("toolu_789", "search_tool", resultData),
        )

        val messages = anthropicAdapter.formatStepMessages(steps)
        assertEquals(2, messages.size)

        // Assistant with tool_use
        val assistantMsg = messages[0]
        assertEquals("assistant", assistantMsg["role"]?.jsonPrimitive?.content)
        val assistantContent = assistantMsg["content"]?.jsonArray
        assertEquals("tool_use", assistantContent?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("toolu_789", assistantContent?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content)

        // User with tool_result
        val userMsg = messages[1]
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)
        val userContent = userMsg["content"]?.jsonArray
        assertEquals("tool_result", userContent?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("toolu_789", userContent?.get(0)?.jsonObject?.get("tool_use_id")?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini adapter formats tools payload as functionDeclarations`() {
        val toolsPayload = geminiAdapter.formatToolsPayload(listOf(sampleTool))
        assertEquals(1, toolsPayload.size)
        val wrapper = toolsPayload[0].jsonObject
        val declarations = wrapper["functionDeclarations"]?.jsonArray
        assertNotNull(declarations)
        assertEquals(1, declarations?.size)
        val decl = declarations?.get(0)?.jsonObject
        assertEquals("search_tool", decl?.get("name")?.jsonPrimitive?.content)
        assertEquals(sampleSchema, decl?.get("parameters")?.jsonObject)
    }

    @Test
    fun `gemini adapter parses functionCall parts with call ID correlation`() {
        val candidates = buildJsonArray {
            add(buildJsonObject {
                put("content", buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("functionCall", buildJsonObject {
                                put("id", "gemini_call_101")
                                put("name", "search_tool")
                                put("args", buildJsonObject { put("query", "Gemini 2.0") })
                            })
                        })
                    })
                })
            })
        }

        val parsedCalls = geminiAdapter.parseToolCalls(candidates)
        assertEquals(1, parsedCalls.size)
        val call = parsedCalls[0]
        assertEquals("gemini_call_101", call.callId)
        assertEquals("search_tool", call.toolName)
        assertEquals("Gemini 2.0", call.arguments["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini adapter formats step contents with role model and user functionResponse`() {
        val call = ToolCall("call_xyz", "search_tool", buildJsonObject { put("query", "test") })
        val resultData = buildJsonObject { put("matches", 5) }
        val steps = listOf(
            ModelRoundStep.AssistantToolCalls(listOf(call)),
            ModelRoundStep.ToolExecutionFeedback("call_xyz", "search_tool", resultData),
        )

        val contents = geminiAdapter.formatStepContents(steps)
        assertEquals(2, contents.size)

        // 1. role="model" with functionCall
        val modelContent = contents[0]
        assertEquals("model", modelContent["role"]?.jsonPrimitive?.content)
        val modelParts = modelContent["parts"]?.jsonArray
        val funcCall = modelParts?.get(0)?.jsonObject?.get("functionCall")?.jsonObject
        assertEquals("search_tool", funcCall?.get("name")?.jsonPrimitive?.content)
        assertEquals("call_xyz", funcCall?.get("id")?.jsonPrimitive?.content)

        // 2. role="user" with functionResponse
        val userContent = contents[1]
        assertEquals("user", userContent["role"]?.jsonPrimitive?.content)
        val userParts = userContent["parts"]?.jsonArray
        val funcResponse = userParts?.get(0)?.jsonObject?.get("functionResponse")?.jsonObject
        assertEquals("search_tool", funcResponse?.get("name")?.jsonPrimitive?.content)
        assertEquals("call_xyz", funcResponse?.get("id")?.jsonPrimitive?.content)
        val output = funcResponse?.get("response")?.jsonObject?.get("output")?.jsonObject
        assertEquals(resultData, output)
    }

    @Test
    fun `disabled tool is omitted from tools payload`() {
        val disabledTool = sampleTool.copy(enabled = false)
        assertTrue(openAiAdapter.formatToolsPayload(listOf(disabledTool)).isEmpty())
        assertTrue(anthropicAdapter.formatToolsPayload(listOf(disabledTool)).isEmpty())
        assertTrue(geminiAdapter.formatToolsPayload(listOf(disabledTool)).isEmpty())
    }
}
