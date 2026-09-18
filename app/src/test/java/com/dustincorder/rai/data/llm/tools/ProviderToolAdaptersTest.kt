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

    @Test
    fun `gemini generates unique synthetic call IDs for same-name function calls without id`() {
        val candidates = buildJsonArray {
            add(buildJsonObject {
                put("content", buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("functionCall", buildJsonObject {
                                put("name", "weather_tool")
                                put("args", buildJsonObject { put("city", "Paris") })
                            })
                        })
                        add(buildJsonObject {
                            put("functionCall", buildJsonObject {
                                put("name", "weather_tool")
                                put("args", buildJsonObject { put("city", "London") })
                            })
                        })
                    })
                })
            })
        }

        val parsed = geminiAdapter.parseToolCalls(candidates)
        assertEquals(2, parsed.size)
        assertEquals("gemini_call_weather_tool_0", parsed[0].callId)
        assertEquals("gemini_call_weather_tool_1", parsed[1].callId)
        assertEquals("Paris", parsed[0].arguments["city"]?.jsonPrimitive?.content)
        assertEquals("London", parsed[1].arguments["city"]?.jsonPrimitive?.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gemini fails closed on duplicate call IDs in single batch`() {
        val candidates = buildJsonArray {
            add(buildJsonObject {
                put("content", buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("functionCall", buildJsonObject {
                                put("id", "dup_id")
                                put("name", "tool_a")
                                put("args", buildJsonObject {})
                            })
                        })
                        add(buildJsonObject {
                            put("functionCall", buildJsonObject {
                                put("id", "dup_id")
                                put("name", "tool_b")
                                put("args", buildJsonObject {})
                            })
                        })
                    })
                })
            })
        }
        geminiAdapter.parseToolCalls(candidates)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `openai fails closed on duplicate call IDs in single batch`() {
        val msg = buildJsonObject {
            put("role", "assistant")
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", "same_id")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "tool_1")
                        put("arguments", "{}")
                    })
                })
                add(buildJsonObject {
                    put("id", "same_id")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "tool_2")
                        put("arguments", "{}")
                    })
                })
            })
        }
        openAiAdapter.parseToolCalls(msg)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `anthropic fails closed on duplicate call IDs in single batch`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "tool_use")
                put("id", "same_id")
                put("name", "tool_1")
                put("input", buildJsonObject {})
            })
            add(buildJsonObject {
                put("type", "tool_use")
                put("id", "same_id")
                put("name", "tool_2")
                put("input", buildJsonObject {})
            })
        }
        anthropicAdapter.parseToolCalls(content)
    }

    @Test
    fun `nested objects and array schemas are supported across providers`() {
        val complexSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("user", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("name", buildJsonObject { put("type", "string") })
                        put("roles", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                    })
                })
            })
        }

        assertTrue(openAiAdapter.project(complexSchema) is ProviderSchemaProjection.Supported)
        assertTrue(anthropicAdapter.project(complexSchema) is ProviderSchemaProjection.Supported)
        assertTrue(geminiAdapter.project(complexSchema) is ProviderSchemaProjection.Supported)
    }

    @Test
    fun `enum schemas are supported across providers`() {
        val enumSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("status", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("pending"))
                        add(JsonPrimitive("active"))
                        add(JsonPrimitive("completed"))
                    })
                })
            })
        }

        assertTrue(openAiAdapter.project(enumSchema) is ProviderSchemaProjection.Supported)
        assertTrue(anthropicAdapter.project(enumSchema) is ProviderSchemaProjection.Supported)
        assertTrue(geminiAdapter.project(enumSchema) is ProviderSchemaProjection.Supported)
    }

    @Test
    fun `additionalProperties is unsupported in Gemini but supported in OpenAI`() {
        val schemaWithAdditional = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("key", buildJsonObject { put("type", "string") })
            })
            put("additionalProperties", JsonPrimitive(false))
        }

        assertTrue(openAiAdapter.project(schemaWithAdditional) is ProviderSchemaProjection.Supported)
        val geminiProj = geminiAdapter.project(schemaWithAdditional)
        assertTrue("Gemini must mark additionalProperties as Unsupported", geminiProj is ProviderSchemaProjection.Unsupported)
    }

    @Test
    fun `oneOf and anyOf are unsupported in Gemini but supported in Anthropic`() {
        val schemaWithOneOf = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("contact", buildJsonObject {
                    put("oneOf", buildJsonArray {
                        add(buildJsonObject { put("type", "string") })
                        add(buildJsonObject { put("type", "integer") })
                    })
                })
            })
        }

        val geminiProj = geminiAdapter.project(schemaWithOneOf)
        assertTrue("Gemini must reject oneOf as Unsupported", geminiProj is ProviderSchemaProjection.Unsupported)

        val anthropicProj = anthropicAdapter.project(schemaWithOneOf)
        assertTrue("Anthropic must support oneOf", anthropicProj is ProviderSchemaProjection.Supported)
    }

    @Test
    fun `defs and local ref are supported in Anthropic and OpenAI but unsupported in Gemini`() {
        val schemaWithDefs = buildJsonObject {
            put("type", "object")
            put("\$defs", buildJsonObject {
                put("Address", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("city", buildJsonObject { put("type", "string") })
                    })
                })
            })
            put("properties", buildJsonObject {
                put("home", buildJsonObject {
                    put("\$ref", JsonPrimitive("#/\$defs/Address"))
                })
            })
        }

        val geminiProj = geminiAdapter.project(schemaWithDefs)
        assertTrue("Gemini must reject \$defs/\$ref", geminiProj is ProviderSchemaProjection.Unsupported)

        val openAiProj = openAiAdapter.project(schemaWithDefs)
        assertTrue("OpenAI must support internal \$defs/\$ref", openAiProj is ProviderSchemaProjection.Supported)

        val anthropicProj = anthropicAdapter.project(schemaWithDefs)
        assertTrue("Anthropic must support internal \$defs/\$ref", anthropicProj is ProviderSchemaProjection.Supported)
    }

    @Test
    fun `external unresolvable ref is unsupported in all providers`() {
        val externalRefSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("data", buildJsonObject {
                    put("\$ref", JsonPrimitive("https://schema.example.com/item.json"))
                })
            })
        }

        assertTrue(openAiAdapter.project(externalRefSchema) is ProviderSchemaProjection.Unsupported)
        assertTrue(anthropicAdapter.project(externalRefSchema) is ProviderSchemaProjection.Unsupported)
        assertTrue(geminiAdapter.project(externalRefSchema) is ProviderSchemaProjection.Unsupported)
    }

    @Test
    fun `non-object root schema is unsupported in Anthropic and OpenAI`() {
        val stringRoot = buildJsonObject {
            put("type", "string")
        }

        assertTrue(openAiAdapter.project(stringRoot) is ProviderSchemaProjection.Unsupported)
        assertTrue(anthropicAdapter.project(stringRoot) is ProviderSchemaProjection.Unsupported)
        assertTrue(geminiAdapter.project(stringRoot) is ProviderSchemaProjection.Unsupported)
    }
}
