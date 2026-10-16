package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test

class EmbeddingResponseTest {
    @Test
    fun `out of order batch results retain request associations`() {
        val data = Json.parseToJsonElement("""[{"index":1,"embedding":[0,1]},{"index":0,"embedding":[1,0]}]""").jsonArray
        assertEquals(listOf(listOf(1f, 0f), listOf(0f, 1f)), parseEmbeddingVectors(data, 2))
    }

    @Test
    fun `partial duplicate zero inconsistent and nonfinite vectors are rejected`() {
        val bad = listOf(
            """[{"index":0,"embedding":[1]}]""",
            """[{"index":0,"embedding":[1]},{"index":0,"embedding":[1]}]""",
            """[{"index":0,"embedding":[0]},{"index":1,"embedding":[1]}]""",
            """[{"index":0,"embedding":[1,0]},{"index":1,"embedding":[1]}]""",
            """[{"index":0,"embedding":["NaN"]},{"index":1,"embedding":[1]}]""",
            """[{"embedding":[1]},{"embedding":[1]}]""",
        )
        bad.forEach { raw ->
            try { parseEmbeddingVectors(Json.parseToJsonElement(raw).jsonArray, 2); fail(raw) } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
    }
}
