/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class McpSchemaTest {
    @Test
    fun `mcp conversion preserves definitions`() {
        val source = ToolSchema(
            properties = buildJsonObject {
                put("draft", buildJsonObject { put("\$ref", "#/\$defs/Draft") })
            },
            defs = buildJsonObject {
                put("Draft", buildJsonObject { put("type", "object") })
            },
        )

        val converted = source.toSchema() as InputSchema.Obj

        assertEquals("#/\$defs/Draft", converted.properties["draft"]
            ?.jsonObject?.get("\$ref")?.toString()?.trim('"'))
        assertNotNull(converted.defs?.get("Draft"))
    }
}
