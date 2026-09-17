/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class InputSchemaTest {
    @Test
    fun `object schema keeps definitions used by local references`() {
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("draft", buildJsonObject {
                    put("\$ref", "#/\$defs/Draft")
                })
            },
            defs = buildJsonObject {
                put("Draft", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("content", buildJsonObject { put("type", "string") })
                    })
                })
            },
        )

        val encoded = json.encodeToJsonElement(schema).jsonObject

        assertEquals("#/\$defs/Draft", encoded["properties"]
            ?.jsonObject?.get("draft")?.jsonObject?.get("\$ref")?.toString()?.trim('"'))
        assertNotNull(encoded["\$defs"]?.jsonObject?.get("Draft"))
    }
}
