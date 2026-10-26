package me.rerere.rikkahub.data.music

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

fun createSharedMusicTool(
    manager: SharedMusicManager,
    assistantId: String,
    conversationId: String,
) = run {
    val target = manager.connection.value
    Tool(
        name = "shared_music",
        description =
            "和用户一起听当前选中的音乐平台（网易云或 QQ 音乐）。你读歌曲、进度和歌词，听不到音频。可查当前播放、搜歌、读歌词、分享歌卡、排歌。" +
                "play_now 在用户正听歌时切歌，否则备好歌曲等她点击播放。删歌、排序和播放模式由用户控制。",
        parameters = {
            InputSchema.Obj(
                properties =
                    buildJsonObject {
                        put(
                            "action",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        listOf(
                                                "now_playing",
                                                "search",
                                                "lyrics",
                                                "queue",
                                                "share",
                                                "play_next",
                                                "queue_add",
                                                "play_now",
                                            )
                                            .forEach { add(it) }
                                    },
                                )
                            },
                        )
                        put(
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "search 的歌曲或歌手关键词")
                            },
                        )
                        put(
                            "songId",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "从搜索或播放状态取得的歌曲 ID")
                            },
                        )
                        put(
                            "note",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "给她的简短留言，最多 300 字")
                            },
                        )
                        put(
                            "lineIndexes",
                            buildJsonObject {
                                put("type", "array")
                                put("maxItems", 8)
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("minimum", 0)
                                    },
                                )
                                put("description", "share 可选歌词行号，先用 lyrics 获取；请优先分享歌卡，避免整段复述歌词")
                            },
                        )
                    },
                required = listOf("action"),
            )
        },
        execute = { manager.executeTool(assistantId, conversationId, it.jsonObject, target) },
    )
}
