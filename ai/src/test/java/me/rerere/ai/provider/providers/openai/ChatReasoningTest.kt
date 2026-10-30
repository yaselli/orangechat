package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatReasoningTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `plain fields take precedence without duplicating relay details`() {
        assertEquals(
            "original",
            chatReasoningText(
                obj(
                    """{
            "reasoning_content":"original", "reasoning":"duplicate",
            "reasoning_details":[{"type":"reasoning.text","text":"duplicate again"}]
        }"""
                )
            ),
        )
        assertEquals(
            "fallback",
            chatReasoningText(obj("""{"reasoning_content":"","reasoning":"fallback"}""")),
        )
    }

    @Test
    fun `visible details survive while encrypted and malformed items stay invisible`() {
        assertEquals(
            "first\nsummary\nlast",
            chatReasoningText(
                obj(
                    """{
            "reasoning_content":null, "reasoning_details":[null,42,
                {"type":"reasoning.encrypted","data":"opaque-secret","text":"not visible"},
                {"type":"reasoning.text","text":"first"},
                {"type":"reasoning.summary","summary":"summary"},
                {"type":"unknown","text":"not visible"},
                {"type":"reasoning.text","text":{"unexpected":true}},
                {"type":"reasoning.text","text":"last"}]
        }"""
                )
            ),
        )
        assertNull(
            chatReasoningText(
                obj(
                    """{
            "reasoning_details":[{"type":"reasoning.encrypted","data":"opaque-secret"}]
        }"""
                )
            )
        )
    }

    @Test
    fun `stream deltas preserve whitespace and do not invent separators between requests`() {
        val deltas = listOf("first", " ", "next")
        val text =
            deltas.joinToString("") { delta ->
                chatReasoningText(
                    obj("""{"reasoning_details":[{"type":"reasoning.text","text":"$delta"}]}""")
                )!!
            }
        assertEquals("first next", text)
    }

    @Test
    fun `Mistral arrays keep every thinking block and the actual answer`() {
        val message =
            obj(
                """{"content":[
            {"type":"text","text":"answer "},
            {"type":"thinking","thinking":[{"type":"text","text":"first "},{"type":"text","text":"thought"}]},
            {"type":"thinking","thinking":"second thought"},
            {"type":"text","text":"continues"}
        ]}"""
            )
        assertEquals("first thought\nsecond thought", chatReasoningText(message))
        assertEquals("answer continues", chatContentText(message["content"]))
    }

    @Test
    fun `missing and malformed reasoning never converts ordinary prose into thinking`() {
        for (message in
            listOf(
                """{"content":"Let me think about this. Here is the answer."}""",
                """{"reasoning":false,"reasoning_content":123,"reasoning_details":{}}""",
                """{"reasoning_details":[{"type":"reasoning.text","text":""}]}""",
            )) assertNull(chatReasoningText(obj(message)))
        assertEquals("  answer\n", chatContentText(obj("""{"content":"  answer\n"}""")["content"]))
        assertEquals("", chatContentText(obj("""{"content":null}""")["content"]))
    }
}
