package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.data.service.nextProactiveAllowedTime

internal fun createProactiveWakeupTool(
    context: Context,
    store: SettingsStore,
    conversations: ConversationRepository,
    assistantId: String,
    conversationId: String,
): Tool =
    Tool(
        name = "proactive_wakeup",
        description =
            "约定你下次几点几分主动来找当前用户。schedule 保存当前会话的下一次约定，get 查看，cancel 取消当前会话待执行/执行中的约定。到点重新读取会话生成回复，不是系统闹钟。",
        systemPrompt = { _, _ ->
            "你可以根据聊天自然决定下次来找用户的具体时间，用 proactive_wakeup 实际保存后再说约好了；不要只口头承诺。" +
                "每个会话保留一个待执行约定，新约定仅替换同一会话的旧约定。有约定时暂停随机提前检查，用户可在主动消息设置中取消。" +
                "遵守总开关和免打扰；后台限制或断网可能延迟。当前手机时间：" +
                java.time.ZonedDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))
        },
        parameters = {
            InputSchema.Obj(
                properties =
                    buildJsonObject {
                        putJsonObject("action") {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray {
                                    add("schedule")
                                    add("get")
                                    add("cancel")
                                },
                            )
                        }
                        putJsonObject("at") {
                            put("type", "string")
                            put("description", "schedule 必填，未来的手机本地时间 yyyy-MM-dd HH:mm，精确到分钟，包含日期")
                        }
                        putJsonObject("reason") {
                            put("type", "string")
                            put("description", "schedule 必填，届时想聊的事或约定背景，最多500字；不是要照搬发送的固定消息")
                        }
                    },
                required = listOf("action"),
            )
        },
        execute = { arguments ->
            try {
                val args = arguments.jsonObject
                val action = args["action"]?.jsonPrimitive?.content ?: error("缺少 action")
                require(action in setOf("schedule", "get", "cancel")) { "未知 action" }
                val conversation = conversations.getConversationById(Uuid.parse(conversationId))
                require(conversation?.assistantId?.toString() == assistantId) { "原会话已不存在或角色不匹配" }
                val now = System.currentTimeMillis()
                val zone = ZoneId.systemDefault()
                val wakeup =
                    if (action == "schedule") {
                        val at =
                            parseProactiveWakeupTime(
                                args["at"]?.jsonPrimitive?.content.orEmpty(),
                                zone,
                                now,
                            )
                        val reason = args["reason"]?.jsonPrimitive?.content.orEmpty().trim()
                        require(reason.isNotBlank() && reason.length <= 500) { "请填写1到500字的约定背景" }
                        ProactiveWakeup(
                            Uuid.random().toString(),
                            assistantId,
                            conversationId,
                            at,
                            zone.id,
                            reason,
                        )
                    } else null
                if (action != "get")
                    store.update { settings ->
                        val setting = settings.proactiveMessageSetting
                        if (action == "schedule")
                            require(setting.enabled && setting.allowAiWakeups) { "用户尚未开启 AI 主动唤醒" }
                        val updated =
                            if (wakeup != null) setting.aiWakeups.scheduleWakeup(wakeup)
                            else
                                setting.aiWakeups.map { old ->
                                    if (
                                        old.assistantId == assistantId &&
                                            old.conversationId == conversationId &&
                                            old.status in
                                                setOf(
                                                    ProactiveWakeupStatus.Pending,
                                                    ProactiveWakeupStatus.Running,
                                                )
                                    )
                                        old.copy(
                                            status = ProactiveWakeupStatus.Cancelled,
                                            outcome = "AI 应用户要求取消",
                                        )
                                    else old
                                }
                        settings.copy(
                            proactiveMessageSetting =
                                setting.copy(aiWakeups = updated.trimWakeupHistory())
                        )
                    }
                val setting = store.settingsFlowRaw.first().proactiveMessageSetting
                if (action != "get") ProactiveMessageService.scheduleNext(context, setting)
                val result = buildJsonObject {
                    put("success", true)
                    put("enabled", setting.enabled && setting.allowAiWakeups)
                    wakeup?.let {
                        val effective = nextProactiveAllowedTime(setting, it.atMillis, zone)
                        put("scheduled_at", Instant.ofEpochMilli(effective).atZone(zone).toString())
                        put("quiet_hours_deferred", effective != it.atMillis)
                    }
                    put(
                        "appointments",
                        buildJsonArray {
                            setting.aiWakeups
                                .filter {
                                    it.assistantId == assistantId &&
                                        it.conversationId == conversationId
                                }
                                .forEach { item ->
                                    add(
                                        buildJsonObject {
                                            put("id", item.id)
                                            put(
                                                "at",
                                                Instant.ofEpochMilli(item.atMillis)
                                                    .atZone(ZoneId.of(item.zoneId))
                                                    .toString(),
                                            )
                                            put("status", item.status.name)
                                            put("reason", item.reason)
                                            put("outcome", item.outcome)
                                        }
                                    )
                                }
                        },
                    )
                }
                listOf(UIMessagePart.Text(result.toString()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", false)
                            put("error", error.message ?: "主动唤醒安排失败")
                        }
                            .toString()
                    )
                )
            }
        },
    )
