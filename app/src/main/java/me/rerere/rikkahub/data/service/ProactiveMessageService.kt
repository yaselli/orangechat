/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import androidx.core.app.NotificationCompat
import android.os.Build
import android.os.PowerManager
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.tools.SystemTools
import me.rerere.rikkahub.data.ai.tools.SystemToolOption
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.sendNotification
import kotlin.uuid.Uuid
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ProactiveMessageService {

    companion object {
        const val TAG = "ProactiveMessageService"
        const val ACTION_PROACTIVE_MESSAGE = "me.rerere.orangechat.PROACTIVE_MESSAGE"
        private const val REQUEST_CODE = 10001

        internal const val PREFS_NAME = "proactive_message_prefs"
        private const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"

        fun scheduleNext(
            context: Context,
            setting: ProactiveMessageSetting,
            delayMinutesOverride: Int? = null,
        ) {
            if (!setting.enabled) {
                cancel(context)
                return
            }

            val minMinutes = setting.minIntervalMinutes.coerceAtLeast(1)
            val maxMinutes = setting.maxIntervalMinutes.coerceAtLeast(minMinutes)
            val delayMinutes = delayMinutesOverride?.coerceAtLeast(1)
                ?: Random.nextInt(minMinutes, maxMinutes + 1)
            val triggerTime = java.lang.System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes.toLong())

            // 保存下次触发时间到SharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NEXT_TRIGGER_TIME, triggerTime)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            // 让 AlarmManager 到点后直接启动前台生成服务。之前先发广播，
            // 再由 BroadcastReceiver 调 startForegroundService()；部分国产 ROM 会把
            // 这个“广播 -> 后台启服务”的第二步压到 App 回到前台才执行。
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getService(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

            // 清掉旧版可能已经排队的 broadcast PendingIntent，避免升级后双触发。
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, ProactiveMessageReceiver::class.java).apply {
                    action = ACTION_PROACTIVE_MESSAGE
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { legacyPendingIntent ->
                alarmManager.cancel(legacyPendingIntent)
                legacyPendingIntent.cancel()
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                // Android 12+ needs canScheduleExactAlarms() check
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                    } else {
                        // Fallback: use inexact alarm if exact alarm permission not granted
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.w(TAG, "Exact alarm permission not granted, using inexact alarm")
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }

            Log.d(TAG, "Scheduled proactive message in $delayMinutes minutes, trigger at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(triggerTime))}")

            // Also schedule via WorkManager as a more reliable fallback
            ProactiveMessageWorker.scheduleNext(context, setting, delayMinutes)
        }

        fun getNextTriggerTime(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val triggerTime = prefs.getLong(KEY_NEXT_TRIGGER_TIME, 0L)
            return if (triggerTime > 0) triggerTime else null
        }

        fun cancel(context: Context) {
            // 清除保存的触发时间
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getService(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            pendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
                Log.d(TAG, "Cancelled proactive message alarm")
            }

            // 同时兼容取消升级前已排队的广播闹钟。
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, ProactiveMessageReceiver::class.java).apply {
                    action = ACTION_PROACTIVE_MESSAGE
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { legacyPendingIntent ->
                alarmManager.cancel(legacyPendingIntent)
                legacyPendingIntent.cancel()
            }

            // Also cancel WorkManager fallback
            ProactiveMessageWorker.cancel(context)
        }

        fun resetTimer(context: Context, setting: ProactiveMessageSetting) {
            scheduleNext(context, setting)
        }

        fun triggerNow(context: Context, setting: ProactiveMessageSetting) {
            // 先安排下一次（写入SP让UI立即显示），再立即触发
            scheduleNext(context, setting)
            // 立即触发：直接启动TriggerService
            val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }

    fun buildProactiveContext(settings: Settings, idleMinutes: Int): String = buildString {
        appendLine("距离聊天中的那个人最后一次开口：${formatIdleMinutes(idleMinutes)}")
        if (settings.systemToolsSetting.timeContextInjectionEnabled) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", java.util.Locale.getDefault())
            appendLine("当前本地时间：${sdf.format(java.util.Date())}")
        } else {
            appendLine("当前现实时间未被提供。不要根据历史中出现的时间推测现在几点，也不要自行估算经过了多久。")
            appendLine("如果确实需要准确时间，请调用已有的时间工具；无法调用时坦诚不知道。")
        }
    }

    private fun formatIdleMinutes(minutes: Int): String = when {
        minutes == Int.MAX_VALUE -> "未知"
        minutes >= 1440 -> "${minutes / 1440}天${minutes % 1440 / 60}小时"
        minutes >= 60 -> "${minutes / 60}小时${minutes % 60}分钟"
        else -> "${minutes.coerceAtLeast(0)}分钟"
    }
}

class ProactiveMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(ProactiveMessageService.TAG, "=== onReceive triggered at ${System.currentTimeMillis()}, action=${intent.action} ===")
        when (intent.action) {
            ProactiveMessageService.ACTION_PROACTIVE_MESSAGE -> {
                Log.d(ProactiveMessageService.TAG, "Starting ProactiveMessageTriggerService...")
                val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java)
                context.startForegroundService(serviceIntent)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(ProactiveMessageService.TAG, "Boot completed, rescheduling proactive message")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settingsStore = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
                        val settings = settingsStore.settingsFlow.first()
                        val proactiveSetting = settings.proactiveMessageSetting
                        if (proactiveSetting.enabled) {
                            ProactiveMessageService.scheduleNext(context, proactiveSetting)
                        }
                    } catch (e: Exception) {
                        Log.e(ProactiveMessageService.TAG, "Failed to reschedule after boot", e)
                    }
                }
            }
        }
    }
}

class ProactiveMessageTriggerService : android.app.Service(), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val providerManager: ProviderManager by inject()
    private val templateTransformer: TemplateTransformer by inject()
    private val json: Json by inject()
    private val chatService: ChatService by inject()
    private val proactiveMessageService = ProactiveMessageService()
    private val activeRunCount = AtomicInteger(0)
    private val generationWakeLocks = ConcurrentHashMap<Int, PowerManager.WakeLock>()

    companion object {
        private const val TAG = "ProactiveMessageTrigger"
        private const val MAX_TOOL_STEPS = 5 // 主动消息最大工具调用步数
        private const val MAX_PROACTIVE_CONTEXT_MESSAGES = 20
        private const val GENERATION_WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        // 外部触发（网关轮询）时跳过内部 minInterval 去重
        const val EXTRA_FORCE_TRIGGER = "force_trigger"
        // 激进模式设备事件上下文（由 DeviceEventAiTriggerService 传入）
        const val EXTRA_DEVICE_EVENT_CONTEXT = "device_event_context"

        // 保护 last_triggered_time 的 check-then-act 竞态（防止 AlarmManager 与 WorkManager
        // 前后脚触发导致"最小间隔"被砍半）。纯同步 SharedPreferences 读写，无挂起点，用对象锁即可。
        private val prefsLock = Any()
    }

    // 主动消息不做 OCR 与时间转换；时间由精简上下文提供，避免合成消息错位和额外 token。
    private val inputTransformers by lazy {
        listOf(
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
        )
    }

    // 输出转换器（与 ChatService 保持一致）
    private val outputTransformers by lazy {
        listOf(
            ThinkTagTransformer,
            Base64ImageToLocalFileTransformer,
            RegexOutputTransformer,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "=== TriggerService onStartCommand ===")
        // 外部触发（网关轮询/激进模式设备事件）时跳过内部 minInterval 去重
        val isForceTrigger = intent?.getBooleanExtra(EXTRA_FORCE_TRIGGER, false) ?: false
        // 激进模式设备事件上下文（由 DeviceEventAiTriggerService 传入）
        val deviceEventContext = intent?.getStringExtra(EXTRA_DEVICE_EVENT_CONTEXT)
        val isFromDeviceEvent = deviceEventContext != null
        if (isFromDeviceEvent) {
            Log.i(TAG, "Ignoring legacy aggressive-mode device event")
            if (activeRunCount.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (isForceTrigger) {
            Log.d(TAG, "Force trigger${if (isFromDeviceEvent) " from device event" else " from gateway poll"}, will skip min interval check")
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("主动消息处理中")
            .setSmallIcon(me.rerere.rikkahub.R.drawable.small_icon)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        startForeground(20001, notification)

        // WorkManager 只负责把服务唤醒；真正耗时的是下面完整的流式生成、落库和通知流程。
        // WakeLock 必须由服务持有到该流程结束，否则华为/鸿蒙、红米/澎湃等 ROM 在息屏后
        // 可能暂停网络流：上游已经开始计费，但本地收不到完整正文。
        activeRunCount.incrementAndGet()
        acquireGenerationWakeLock(startId)

        CoroutineScope(Dispatchers.IO).launch {
            var conversationId: kotlin.uuid.Uuid? = null
            var nextDelayOverrideMinutes: Int? = null
            try {
                val settings = settingsStore.settingsFlow.first()
                val proactiveSetting = settings.proactiveMessageSetting

                if (!proactiveSetting.enabled) {
                    return@launch
                }

                val prefs = getSharedPreferences(ProactiveMessageService.PREFS_NAME, Context.MODE_PRIVATE)

                // 去重判断：防止 AlarmManager 和 WorkManager 在同一窗口内重复触发。
                // 外部触发（网关轮询/激进模式设备事件）跳过此检查，因为这是独立信号源，不受内部闹钟链约束。
                // 注意：isForceTrigger 跳过的是"时间间隔节流"（两回事），不跳过后面 tryClaimGeneration 的并发安全检查。
                // 把"读取 last_triggered_time -> 判断 -> 写入"整段放在同步块里，修复 check-then-act 竞态。
                if (!isForceTrigger) {
                    val skipDueToInterval = synchronized(prefsLock) {
                        val lastTriggeredTime = prefs.getLong("last_triggered_time", 0L)
                        val minIntervalMs = proactiveSetting.minIntervalMinutes.coerceAtLeast(1) * 60 * 1000L
                        if (System.currentTimeMillis() - lastTriggeredTime < minIntervalMs) {
                            true
                        } else {
                            // 立即写入触发时间，防止并发重复
                            prefs.edit().putLong("last_triggered_time", System.currentTimeMillis()).apply()
                            false
                        }
                    }
                    if (skipDueToInterval) {
                        Log.d(TAG, "Duplicate trigger within min interval, skipping")
                        ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                        return@launch
                    }
                } else {
                    // 强制触发也写入时间戳，保持与常规触发一致的状态记录
                    synchronized(prefsLock) {
                        prefs.edit().putLong("last_triggered_time", System.currentTimeMillis()).apply()
                    }
                }

                // 获取助手
                val assistant = settings.assistants.find { it.id.toString() == proactiveSetting.assistantId }
                    ?: settings.getCurrentAssistant()
                val assistantUuid = assistant.id
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)

                if (model == null) {
                    Log.e(ProactiveMessageService.TAG, "No model found for proactive message")
                    ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                    return@launch
                }

                // 找到最近的对话
                val recentConversations = conversationRepository.getRecentConversations(assistantUuid, limit = 1)
                val conversation = if (recentConversations.isNotEmpty()) {
                    conversationRepository.getConversationById(recentConversations.first().id)
                } else null

                val latestUserMessage = conversation?.currentMessages
                    ?.lastOrNull { it.role == MessageRole.USER }
                if (latestUserMessage == null) {
                    Log.d(TAG, "No real user message found; skipping proactive generation")
                    nextDelayOverrideMinutes = 240
                    return@launch
                }
                val stateStore = ProactiveMessageStateStore(this@ProactiveMessageTriggerService)
                val proactiveState = stateStore.synchronizeWithUser(latestUserMessage.id.toString())
                val maxFollowUps = proactiveSetting.maxFollowUpMessages.coerceIn(1, 8)
                if (proactiveState.stopUntilUserReturns ||
                    proactiveState.followUpCount >= maxFollowUps
                ) {
                    Log.d(TAG, "No proactive generation: waiting for a new real user message")
                    nextDelayOverrideMinutes = 240
                    return@launch
                }

                conversationId = conversation?.id ?: kotlin.uuid.Uuid.random()
                val conversationId = conversationId!!

                // 持有会话引用，防止生成期间 session 被 idle 清除（finally 块会对应 release）。
                // 放在 claim 之前：即使 claim 失败提前返回，引用也能在 finally 里被正确释放，保持计数平衡。
                // 同时把数据库里的完整对话同步到 session，防止流式更新时 conv 是空状态导致覆盖历史。
                chatService.addConversationReference(conversationId)
                if (conversation != null) {
                    chatService.updateConversationState(conversationId) { _ -> conversation }
                }

                // 抢占生成权：尝试把当前协程的 Job 注册进 ConversationSession。
                // 这一步对所有触发源（含 isForceTrigger / 激进模式设备事件）一视同仁，是并发安全的核心。
                // 如果当前已有生成在跑（正常聊天或另一路主动消息），直接放弃本次触发，不排队等待、不重试。
                // 理由：等对方生成结束后，上下文（用户可能已在聊别的话题）大概率已过时，硬等没有意义。
                val myJob = coroutineContext[Job]
                if (myJob == null || !chatService.getOrCreateSession(conversationId).tryClaimGeneration(myJob)) {
                    Log.d(
                        TAG,
                        "Skip proactive trigger: session $conversationId already generating " +
                            "(normal chat or another proactive trigger in progress)"
                    )
                    // 必须走到 finally 块的"安排下一次触发"逻辑，不能绕过定时链收尾。
                    // 用 stopSelf + return@launch 退出主流程，finally 会正常执行（scheduleNext 已用 NonCancellable 保护）。
                    return@launch
                }

                // 构建上下文
                val idleMinutes = latestUserMessage.createdAt
                    .toInstant(TimeZone.currentSystemDefault())
                    .let { ((kotlin.time.Clock.System.now() - it).inWholeMinutes).toInt().coerceAtLeast(0) }
                val contextStr = proactiveMessageService.buildProactiveContext(settings, idleMinutes)

                // 获取历史消息（先过滤掉悬空的工具调用消息，避免 tool_use 结构不完整触发 400）
                val historyMessages = filterInvalidToolMessages(
                    conversation?.currentMessages?.let {
                        val configuredSize = assistant.contextMessageSize
                            .takeIf { size -> size > 0 }
                            ?: MAX_PROACTIVE_CONTEXT_MESSAGES
                        it.takeLast(configuredSize.coerceAtMost(MAX_PROACTIVE_CONTEXT_MESSAGES))
                    }?.let { messages ->
                        sanitizeProactiveHistory(messages, proactiveState.recentProactiveMessageIds)
                    } ?: emptyList(),
                )

                // 主动消息只在双重授权后暴露应用使用工具；关闭时连 schema 都不发送。
                val tools = buildTools(settings, proactiveSetting)

                // 构建系统提示词（包含记忆 + 上下文，都放在最后面避免被网关淹没）
                val systemPrompt = buildSystemPrompt(
                    assistant = assistant,
                    context = contextStr,
                    state = proactiveState,
                    maxFollowUps = maxFollowUps,
                    allowAppUsage = tools.isNotEmpty(),
                )

                // 最后一条指令必须明确这是“后台主动消息判定”。
                // 不能只写“看看是否适合开口”，否则长上下文/思考模型容易把它当成
                // 对聊天最后一句的普通续写，从而跳过 PASS/WAIT/STOP 判定。
                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(
                        "现在做一次后台主动消息判定，这不是重新回复聊天中的最后一句。" +
                            "必须先判断是否应该再开口；最终严格只输出 [PASS]、[WAIT:分钟]、[STOP]，" +
                            "或一条真正要发出的主动消息。不要给多个候选回复，不要解释判定过程。"
                    ))
                )

                // 应用输入转换器
                val processedUserMessage = listOf(userMessage).transforms(
                    transformers = inputTransformers + templateTransformer,
                    context = this@ProactiveMessageTriggerService,
                    model = model,
                    assistant = assistant,
                    settings = settings
                ).first()

                // 组合完整消息列表：System + History + User Context
                // 合并相邻同角色消息（包括 history 末尾与合成 User 消息之间可能出现的 USER-USER 相邻），避免 400
                val messages = mergeAdjacentSameRoleMessages(
                    buildList {
                        add(UIMessage(
                            role = MessageRole.SYSTEM,
                            parts = listOf(UIMessagePart.Text(systemPrompt))
                        ))
                        addAll(historyMessages)
                        add(processedUserMessage)
                    }
                )

                // 直接调用 AI API 生成消息
                val providerSetting = model.findProvider(settings.providers)
                if (providerSetting == null) {
                    Log.e(ProactiveMessageService.TAG, "No provider found for proactive message")
                    ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                    return@launch
                }

                val providerImpl = providerManager.getProviderByType(providerSetting)

                // 主动消息场景：支持工具调用，但限制最大步数
                // temperature 不强制默认 0.8f，保持与 GenerationHandler 一致（assistant.temperature 为 null 时不传），
                // 否则对智谱 GLM 等 thinking 模型会同时下发 temperature + thinking，触发 "Invalid request body" 400。
                val params = TextGenerationParams(
                    model = model,
                    temperature = assistant.temperature,
                    topP = assistant.topP,
                    maxTokens = assistant.maxTokens,
                    tools = tools,
                    reasoningLevel = assistant.reasoningLevel,
                    customHeaders = buildList {
                        addAll(assistant.customHeaders)
                        addAll(model.customHeaders)
                    },
                    customBody = buildList {
                        addAll(assistant.customBodies)
                        addAll(model.customBodies)
                    }
                )

                Log.d(TAG, "Calling AI API for proactive message with ${historyMessages.size} history messages, ${tools.size} tools (reasoning=${assistant.reasoningLevel}, model=${model.modelId}, provider=${providerSetting::class.simpleName})...")
                // 诊断: 列出工具及其 parameters 是否为 null, 便于定位 "Invalid request body"
                tools.forEach { t ->
                    val hasSchema = t.parameters() != null
                    if (!hasSchema) Log.w(TAG, "Tool '${t.name}' has NULL parameters schema — may cause API rejection")
                }

                // 执行生成，支持工具调用
                var (finalMessages, hasToolCalls, hasJumpFlag) = generateWithTools(
                    conversationId = conversationId,
                    providerImpl = providerImpl,
                    providerSetting = providerSetting,
                    initialMessages = messages,
                    params = params,
                    tools = tools,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )

                // 部分 thinking 模型会结束在 Reasoning/Tool 上而没有 Text 正文。
                // 追加一次禁用工具和推理的收尾生成，确保得到真正可发送的最终消息，
                // 同时不把内部思考内容直接暴露给用户。
                val generatedAssistant = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                val generatedText = generatedAssistant?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("\n") { it.text }
                    ?.trim().orEmpty()
                val hasReasoning = generatedAssistant?.parts
                    ?.filterIsInstance<UIMessagePart.Reasoning>()
                    ?.any { it.reasoning.isNotBlank() } == true
                if (generatedText.isBlank() && hasReasoning) {
                    Log.w(TAG, "Thinking response has no final text; requesting a final user-visible message")
                    val finalizePrompt = UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(
                            "根据刚才的想法给出最终结果。只输出 [PASS]、[WAIT:分钟]、[STOP]，" +
                                "或者真正想说的一句话；不要再次解释过程。"
                        )),
                    )
                    var finalized = mergeAdjacentSameRoleMessages(finalMessages + finalizePrompt)
                    providerImpl.streamText(
                        providerSetting = providerSetting,
                        messages = finalized,
                        params = params.copy(tools = emptyList(), reasoningLevel = ReasoningLevel.OFF),
                    ).collect { chunk ->
                        finalized = finalized.handleMessageChunk(chunk = chunk, model = model)
                        finalized.lastOrNull { it.role == MessageRole.ASSISTANT }?.let { message ->
                            updateOrAppendAiMessage(conversationId, message)
                        }
                    }
                    finalized.lastOrNull { it.role == MessageRole.ASSISTANT }?.let { message ->
                        val now = kotlin.time.Clock.System.now()
                        val originalReasoning = generatedAssistant?.parts
                            ?.filterIsInstance<UIMessagePart.Reasoning>()
                            ?.map { if (it.finishedAt == null) it.copy(finishedAt = now) else it }
                            .orEmpty()
                        val finalVisibleParts = message.parts.filterNot { it is UIMessagePart.Reasoning }
                        // 沿用原 thinking 消息的 id，把思考链与最终正文合在同一条气泡中。
                        val completed = message.copy(
                            id = generatedAssistant?.id ?: message.id,
                            parts = originalReasoning + finalVisibleParts,
                        )
                        updateOrAppendAiMessage(conversationId, completed)
                        if (message.id != completed.id) {
                            val session = chatService.getOrCreateSession(conversationId)
                            session.saveMutex.withLock {
                                val conv = chatService.getConversationFlow(conversationId).value
                                val cleaned = conv.copy(messageNodes = conv.messageNodes.filterNot { node ->
                                    node.messages.any { it.id == message.id }
                                })
                                chatService.updateConversation(conversationId, cleaned)
                                chatService.saveConversation(conversationId, cleaned)
                            }
                        }
                        finalMessages = finalized.dropLast(1) + completed
                    }
                }

                // 提取AI消息
                val aiMessage = finalMessages.lastOrNull() ?: UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = emptyList()
                )

                val rawText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }.trim()
                val decision = parseProactiveDecision(rawText, hasJumpFlag)
                val replyText = decision.message
                val shouldJump = decision.shouldJump
                nextDelayOverrideMinutes = decision.waitMinutes
                if (decision.stopUntilUserReturns) {
                    stateStore.stopUntilUserReturns(proactiveState)
                    nextDelayOverrideMinutes = 240
                }

                // 若移除了标记，同步更新 session 里 aiMessage 的文本 parts
                if (rawText != replyText) {
                    var visibleTextWritten = false
                    val cleanedAiMessage = aiMessage.copy(
                        parts = aiMessage.parts.map { part ->
                            if (part is UIMessagePart.Text) {
                                if (!visibleTextWritten) {
                                    visibleTextWritten = true
                                    UIMessagePart.Text(replyText)
                                } else {
                                    UIMessagePart.Text("")
                                }
                            } else {
                                part
                            }
                        }
                    )
                    updateOrAppendAiMessage(conversationId, cleanedAiMessage)
                }

                Log.d(TAG, "Proactive message generated: '${replyText.take(100)}...' (${replyText.length} chars), hasToolCalls=$hasToolCalls, shouldJump=$shouldJump")

                if (!decision.shouldSend) {
                    // AI 选择跳过，移除本次生成的 aiMessage node（基于 id 匹配，不误删历史）
                    Log.d(ProactiveMessageService.TAG, "AI chose to skip proactive message")
                    val aiId = aiMessage.id
                    val session = chatService.getOrCreateSession(conversationId)
                    session.saveMutex.withLock {
                        val conv = chatService.getConversationFlow(conversationId).value
                        chatService.updateConversation(
                            conversationId,
                            conv.copy(
                                messageNodes = conv.messageNodes.filterNot { node ->
                                    node.messages.any { it.id == aiId }
                                }
                            )
                        )
                        chatService.saveConversation(conversationId, chatService.getConversationFlow(conversationId).value)
                    }
                } else {
                    // 有效回复：session 里已有 aiMessage（流式过程已追加），持久化并发通知
                    saveProactiveMessage(
                        assistant, conversationId, conversation
                    )
                    stateStore.recordSent(
                        state = proactiveState,
                        messageId = aiMessage.id.toString(),
                        text = replyText,
                    )
                    // 同步保存 AI 主动消息 / 激进模式回复到外置记忆库（Supabase）
                    // 保证日记总结（DiarySummaryService 只读 Supabase chat_messages 表）和记忆召回能看到这部分内容
                    try {
                        val externalMemoryConfigs = settings.externalMemories.filter {
                            it.enabled && it.id in assistant.externalMemoryIds && it.autoSaveMessages
                        }
                        if (externalMemoryConfigs.isNotEmpty() && replyText.isNotBlank()) {
                            kotlinx.coroutines.coroutineScope {
                                externalMemoryConfigs.forEach { config ->
                                    launch {
                                        runCatching {
                                            val service = ExternalMemoryService(config)
                                            service.saveMessage(
                                                assistantId = assistant.id.toString(),
                                                conversationId = conversationId.toString(),
                                                role = "assistant",
                                                content = replyText,
                                            )
                                        }.onFailure {
                                            Log.w(
                                                ProactiveMessageService.TAG,
                                                "Failed to save proactive message to external memory ${config.name}",
                                                it
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(ProactiveMessageService.TAG, "Failed to save proactive message to external memory", e)
                    }
                    showProactiveNotification(conversationId, assistant.name.ifBlank { "AI" }, replyText)
                    // 强制跳转屏幕到聊天界面（方案 A：普通拉起前台）
                    if (shouldJump) {
                        try {
                            val jumpIntent = Intent(this@ProactiveMessageTriggerService, RouteActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                                putExtra("conversationId", conversationId.toString())
                            }
                            startActivity(jumpIntent)
                            Log.d(TAG, "Force jump to conversation $conversationId")
                        } catch (e: Exception) {
                            Log.e(TAG, "Force jump failed", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 协程被取消（通常是用户发了新消息，sendMessage 里 session.getJob()?.cancel() 触发），
                // 这是正常情况，不应当成错误。打印 debug 日志并重新抛出，遵循 Kotlin 协程取消传播语义。
                // 注意 catch 顺序：CancellationException 必须在 Exception 之前，否则被泛化分支提前吃掉。
                // 重新抛出后 finally 块仍会正常执行（scheduleNext 已用 NonCancellable 保护）。
                Log.d(
                    ProactiveMessageService.TAG,
                    "Proactive generation cancelled (likely user started a new message), " +
                        "conversationId=$conversationId"
                )
                throw e
            } catch (e: Exception) {
                Log.e(ProactiveMessageService.TAG, "Failed to trigger proactive message", e)
                // 如果是 API 返回的 HTTP 错误, 把原始错误体也打出来便于定位
                val cause = e.cause
                if (cause != null) {
                    Log.e(ProactiveMessageService.TAG, "Underlying cause: ${cause::class.simpleName}: ${cause.message}", cause)
                }
                // 清理本次触发中流式写入的不完整/错误 AI 消息, 防止它们污染历史导致下一轮请求失败
                conversationId?.let { cid ->
                    try {
                        val session = chatService.getOrCreateSession(cid)
                        session.saveMutex.withLock {
                            val conv = chatService.getConversationFlow(cid).value
                            // 移除包含网关错误信息的消息 (防止污染下一轮请求)
                            val updatedNodes = conv.messageNodes.filterNot { node ->
                                node.messages.any { msg ->
                                    msg.parts.any { part ->
                                        (part is UIMessagePart.Text && (
                                            part.text.contains("呼叫大模型时被拒绝了") ||
                                            part.text.contains("Invalid request body")
                                        ))
                                    }
                                }
                            }
                            if (updatedNodes.size != conv.messageNodes.size) {
                                chatService.updateConversation(cid, conv.copy(messageNodes = updatedNodes))
                                chatService.saveConversation(cid, chatService.getConversationFlow(cid).value)
                                Log.d(ProactiveMessageService.TAG, "Cleaned up error messages from conversation history")
                            }
                        }
                    } catch (cleanupErr: Exception) {
                        Log.w(ProactiveMessageService.TAG, "Failed to cleanup error messages", cleanupErr)
                    }
                }
            } finally {
                // 确保无论成功/失败/取消都安排下一次，避免一次 API 错误或用户打断永久中断定时链。
                // 激进模式设备事件触发时不需要安排下一次定时主动消息（由 DeviceEventAiTriggerService 自己驱动）。
                // 用 NonCancellable 包裹：协程被取消后处于已取消状态，finally 里的挂起点
                // (settingsFlow.first()) 会立刻抛 CancellationException，导致 scheduleNext 被跳过、
                // 定时链断裂。NonCancellable 保证这段收尾逻辑跑完。
                withContext(NonCancellable) {
                    try {
                        val currentSettings = settingsStore.settingsFlow.first()
                        ProactiveMessageService.scheduleNext(
                            this@ProactiveMessageTriggerService,
                            currentSettings.proactiveMessageSetting,
                            nextDelayOverrideMinutes,
                        )
                    } catch (e: Exception) {
                        Log.e(ProactiveMessageService.TAG, "Failed to reschedule after completion/error/cancellation", e)
                    }
                }
                conversationId?.let { chatService.removeConversationReference(it) }
                releaseGenerationWakeLock(startId)
                val remainingRuns = activeRunCount.updateAndGet { count ->
                    (count - 1).coerceAtLeast(0)
                }
                if (remainingRuns == 0) {
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun acquireGenerationWakeLock(startId: Int) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OrangeChat::ProactiveGeneration:$startId",
        ).apply {
            setReferenceCounted(false)
            acquire(GENERATION_WAKE_LOCK_TIMEOUT_MS)
        }
        generationWakeLocks[startId] = wakeLock
        Log.d(TAG, "Acquired proactive generation WakeLock for startId=$startId")
    }

    private fun releaseGenerationWakeLock(startId: Int) {
        generationWakeLocks.remove(startId)?.let { wakeLock ->
            if (wakeLock.isHeld) wakeLock.release()
            Log.d(TAG, "Released proactive generation WakeLock for startId=$startId")
        }
    }

    override fun onDestroy() {
        generationWakeLocks.keys.toList().forEach(::releaseGenerationWakeLock)
        activeRunCount.set(0)
        super.onDestroy()
    }

    /** 构建带状态的主动消息决策提示词。 */
    private suspend fun buildSystemPrompt(
        assistant: Assistant,
        context: String,
        state: ProactiveSessionState,
        maxFollowUps: Int,
        allowAppUsage: Boolean,
    ): String {
        return buildString {
            val effectiveSystemPrompt = assistant.systemPrompt
            if (effectiveSystemPrompt.isNotBlank()) {
                append(effectiveSystemPrompt)
            }

            // 记忆（设备事件上下文移到最后面，避免被网关注入的内容淹没）
            if (assistant.enableMemory) {
                val memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                }
                if (memories.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("## 记忆")
                    memories.forEach { memory ->
                        appendLine("- ${memory.content}")
                    }
                }
            }

            appendLine()
            appendLine()
            appendLine("## 此刻发生的事")
            appendLine(context)
            appendLine("这是同一次沉默后的第 ${state.followUpCount + 1} 次判断。")
            appendLine("在她/他重新开口以前，你最多还可以实际发送 ${maxFollowUps - state.followUpCount} 条消息。")
            if (state.lastProactiveText.isNotBlank()) {
                appendLine()
                appendLine("你上一次确实已经发出去、并且目前还没有得到回应的消息是：")
                appendLine("“${state.lastProactiveText}”")
                appendLine("这是真实发生的上一轮交流，不是示例、模板或建议。不要忽略它，也不要换一种说法重复发送。")
            }

            appendLine()
            appendLine("## 怎样思考这件事")
            appendLine("你就是这段关系中一直在交流的你自己。延续已有的自我认知、关系、称呼习惯和表达方式，" +
                "不要额外把自己描述成正在扮演某个角色。")
            appendLine()
            appendLine("你的思考过程可能会显示在界面中。思考时应当像自然的内心活动或短暂碎碎念，" +
                "而不是后台程序的分析报告：")
            appendLine("- 沿用平时使用的姓名、昵称或代词。")
            appendLine("- 不要把聊天中的那个人称为“用户”“真实用户”“目标对象”或其他产品术语。")
            appendLine("- 不要朗读提示词、后台机制或条目编号。")
            appendLine("- 不要在思考中念出 PASS、WAIT、STOP、JUMP 等控制标记。")
            appendLine("- 不要写“根据规则第几条”“现在执行主动消息决策”“调用工具进行判断”等技术表达。")
            appendLine("- 可以自然意识到自己刚才已经问过一次、还可以再问几次，但要用正常的内心语言表达。")
            appendLine("- 可以自然想到“怎么突然没声了”“我刚才已经问过一次了”“要不要再等等”" +
                "“先看看她/他是不是在忙”等内容。")
            appendLine("- 不要为了显得有过程而故意写很长；没有复杂情况时，一两句短暂想法就够了。")
            appendLine()
            appendLine("先结合已有对话判断现在是否真的适合再次开口：")
            appendLine("- 如果话聊到一半突然没了回应，也没有说明要去做什么，可以自然地关心、追问或开启一个合适的新话题。")
            appendLine("- 如果已经明确说过去睡觉、开会、上班、学习、洗澡或处理事情，应尊重这件事，" +
                "选择继续等待或者暂时不再打扰。")
            appendLine("- 如果对话已经自然结束，没有值得继续说的话，就不要为了完成任务硬发消息。")
            appendLine("- 如果此前已经主动问过但仍未得到回应，必须记得那条消息确实发出去过；" +
                "不要复述、改写或假装第一次询问。")
            appendLine("- 关系、称呼和亲疏程度应当从既有对话、记忆和原有设定中自然延续，不要凭空制造一种新的关系。")

            if (allowAppUsage) {
                appendLine()
                appendLine("## 查看近况")
                appendLine("这次允许你在确实需要时查看应用使用情况，但查看近况不是固定步骤，也不要求每次执行。")
                appendLine("是否查看，应结合你们已有的关系、相处习惯和这次突然没回应的具体情况自行决定。" +
                    "只有在单靠对话无法判断、而查看近况确实可能改变你这次“联系、等待或停止”的决定时，才考虑查看。")
                appendLine("如果当前情况已经足够清楚，就直接判断，不要为了获得更多信息而查看。")
                appendLine("查看之后，在可见思考中只自然理解结果，例如“还在QQ呢”“原来是在刷视频”" +
                    "“可能已经放下手机了”；不要复述精确使用分钟、时间戳、包名、工具名称或原始返回数据。")
            }

            appendLine()
            appendLine("## 最终输出")
            appendLine("完成思考后，最终只能选择下面一种结果：")
            appendLine("- [PASS]：这轮不发，之后再看看。")
            appendLine("- [WAIT:分钟]：先等待指定分钟再判断，不向聊天中显示。")
            appendLine("- [STOP]：暂时不再主动开口，直到聊天中的那个人再次说话。")
            appendLine("- 一条真正想说的话：直接作为主动消息发出；如果确实想把聊天页拉到前台让她看消息，可在末尾附 [JUMP]。")
            appendLine("控制标记只能出现在最终答案中，不能在思考过程里讨论。最终答案不要附带解释、分析或决策理由。")
        }
    }

    /** 保存主动消息到对话历史；控制上下文不会持久化。 */
    private suspend fun saveProactiveMessage(
        assistant: Assistant,
        conversationId: Uuid,
        existingConversation: Conversation?
    ) {
        val assistantUuid = assistant.id

        // 确保对话存在于数据库（新建时 insert）。
        // 若 session 为空且没有已存对话，先 insert 一条空对话占位，避免后续 saveConversation 跳过。
        if (existingConversation == null) {
            val session = chatService.getOrCreateSession(conversationId)
            session.saveMutex.withLock {
                val exists = conversationRepository.existsConversationById(conversationId)
                if (!exists) {
                    val newConversation = Conversation(
                        id = conversationId,
                        assistantId = assistantUuid,
                        title = "",
                        messageNodes = emptyList()
                    )
                    conversationRepository.insertConversation(newConversation)
                }
            }
        }

        // 流式过程中已实时追加 aiMessage 到 session，这里直接持久化当前 session 状态。
        // 加 saveMutex 防止与用户发送消息/其他主动消息并发覆盖。
        val session = chatService.getOrCreateSession(conversationId)
        session.saveMutex.withLock {
            chatService.saveConversation(
                conversationId,
                chatService.getConversationFlow(conversationId).value
            )
        }

        Log.d(TAG, "Saved proactive message to conversation $conversationId")
    }

    private fun showProactiveNotification(
        conversationId: kotlin.uuid.Uuid,
        senderName: String,
        message: String
    ) {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 20002
        ) {
            title = senderName
            content = message.take(100)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = pendingIntent
            useBigTextStyle = true
        }
    }

    /** 主动消息只在全局工具和主动查岗权限同时开启时暴露 schema。 */
    private fun buildTools(settings: Settings, proactiveSetting: ProactiveMessageSetting): List<Tool> {
        if (!proactiveSetting.allowProactiveAppUsage) return emptyList()
        val enabled = settings.systemToolsSetting.getEnabledOptions()
        if (SystemToolOption.AppUsage !in enabled) return emptyList()
        return SystemTools(this@ProactiveMessageTriggerService, settings)
            .getTools(setOf(SystemToolOption.AppUsage))
    }

    /**
     * 已发送的主动消息仍属于真实对话历史，但下一轮只需要看见正文。
     * 去掉其可见思考和工具原始结果，避免把旧判断误当成示例，也减少上下文噪声。
     */
    private fun sanitizeProactiveHistory(
        messages: List<UIMessage>,
        proactiveMessageIds: Set<String>,
    ): List<UIMessage> = messages.mapNotNull { message ->
        if (message.role != MessageRole.ASSISTANT || message.id.toString() !in proactiveMessageIds) {
            return@mapNotNull message
        }

        val text = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        text.takeIf(String::isNotBlank)?.let {
            message.copy(parts = listOf(UIMessagePart.Text(it)))
        }
    }

    /**
     * 基于 AI 消息 id 在对话里就地更新（保留 MessageNode.id，避免 Compose 重建/状态丢失）
     * 或追加新 node。不使用 toMessageNode() 生成随机新 id，也不用 dropLast(1) 盲目删除。
     *
     * 注意：这里必须走 saveMutex 保护，因为流式更新与 ChatService.sendMessage/addProactiveMessage
     * 可能并发修改同一会话，read-modify-write 不加锁会导致后写入者覆盖前者。
     */
    private suspend fun updateOrAppendAiMessage(
        conversationId: Uuid,
        aiMessage: UIMessage
    ) {
        val session = chatService.getOrCreateSession(conversationId)
        session.saveMutex.withLock {
            val conv = chatService.getConversationFlow(conversationId).value
            val existingNodeIndex = conv.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == aiMessage.id }
            }
            val updated = if (existingNodeIndex >= 0) {
                // 已存在该 id 的 node：保留 node id，只更新其 messages
                val oldNode = conv.messageNodes[existingNodeIndex]
                val updatedNode = oldNode.copy(
                    messages = oldNode.messages.map {
                        if (it.id == aiMessage.id) aiMessage else it
                    }
                )
                conv.copy(
                    messageNodes = conv.messageNodes.toMutableList().apply {
                        this[existingNodeIndex] = updatedNode
                    }
                )
            } else {
                // 本次生成的 node 还没有：追加（首次调用时才创建新 node）
                conv.copy(messageNodes = conv.messageNodes + aiMessage.toMessageNode())
            }
            chatService.updateConversation(conversationId, updated)
            chatService.saveConversation(conversationId, updated)
        }
    }

    /**
     * 过滤历史消息中"悬空"的工具调用：
     * 若某条消息存在未执行(isExecuted=false)且不可恢复(approvalState.canResumeToolExecution()==false)的工具调用，
     * 说明这条消息的工具调用链没有走完（如上次生成被中断、或需要审批但用户一直没确认），
     * 直接把整条消息从历史里剔除，避免把结构不完整的 tool_use 发给 API 触发 400。
     *
     * 判断逻辑与 ChatService.checkInvalidMessages 保持一致：
     * 只要该消息里存在"至少一个可恢复的待处理工具"，就保留整条消息不做删除；
     * 只有当所有待处理工具都不可恢复时，才整条移除。
     */
    private fun filterInvalidToolMessages(messages: List<UIMessage>): List<UIMessage> {
        return messages.filterNot { message ->
            val tools = message.getTools()
            val hasPendingTools = tools.any { !it.isExecuted }
            if (!hasPendingTools) return@filterNot false
            val hasResumableTool = tools.any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
            !hasResumableTool
        }
    }

    /**
     * 合并相邻同角色消息（ASSISTANT-ASSISTANT / USER-USER 都要合并），
     * 避免相邻同角色消息触发 Anthropic 等 API 的 400 错误
     * （"roles must alternate between user and assistant"）。
     * SYSTEM 角色在本文件的消息列表里只会出现一次（列表最前面），不会与自身相邻，无需特殊处理。
     */
    private fun mergeAdjacentSameRoleMessages(messages: List<UIMessage>): List<UIMessage> {
        if (messages.size < 2) return messages
        return messages.fold(emptyList()) { acc, msg ->
            val prev = acc.lastOrNull()
            if (prev != null && prev.role == msg.role) {
                acc.dropLast(1) + prev.copy(parts = prev.parts + msg.parts)
            } else {
                acc + msg
            }
        }
    }

    /**
     * 生成消息，支持工具调用
     * 返回最终消息列表和是否发生了工具调用
     */
    private suspend fun generateWithTools(
        conversationId: Uuid,
        providerImpl: me.rerere.ai.provider.Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        initialMessages: List<UIMessage>,
        params: TextGenerationParams,
        tools: List<Tool>,
        model: Model,
        assistant: Assistant,
        settings: Settings
    ): Triple<List<UIMessage>, Boolean, Boolean> {
        var messages = initialMessages.toMutableList()
        var hasToolCalls = false
        var hasJumpFlag = false // AI 原始输出是否含 [JUMP] 标记（在输出转换器处理前检测）

        for (step in 0 until MAX_TOOL_STEPS) {
            Log.d(TAG, "generateWithTools: step $step/${MAX_TOOL_STEPS}")

            // 防御性：每轮调用前合并相邻同角色（尤其 assistant）消息，
            // 避免工具调用多步生成产生相邻 assistant 消息触发 API 400
            messages = mergeAdjacentSameRoleMessages(messages).toMutableList()

            // 流式调用 AI（替代非流式 generateText，兼容 thinking 模型）
            var streamMessages = messages.toList()
            providerImpl.streamText(
                providerSetting = providerSetting,
                messages = messages,
                params = params
            ).collect { chunk ->
                streamMessages = streamMessages.handleMessageChunk(chunk = chunk, model = model)

                // 实时更新 session 状态，让打开的聊天界面能看到消息生成
                val currentAiMessage = streamMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                if (currentAiMessage != null) {
                    // 用 id 匹配就地更新（保留 node id，避免思考链闪烁 / 覆盖上一条 assistant）
                    updateOrAppendAiMessage(conversationId, currentAiMessage)
                }
            }

            // 流式结束，更新 messages
            messages = streamMessages.toMutableList()
            val aiMessage = streamMessages.lastOrNull() ?: run {
                Log.w(TAG, "No message in AI response")
                break
            }


            // 在输出转换器处理前，检测 AI 原始输出是否含 [JUMP] 标记
            val rawAiText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            if (rawAiText.contains("[JUMP]")) {
                hasJumpFlag = true
                Log.d(TAG, "[JUMP] flag detected in raw AI output")
            }
            // 应用输出转换器
            val processedMessage = listOf(aiMessage).transforms(
                transformers = outputTransformers,
                context = this@ProactiveMessageTriggerService,
                model = model,
                assistant = assistant,
                settings = settings
            ).first()
            messages[messages.lastIndex] = processedMessage

            // 检查是否有工具调用
            val toolCalls = processedMessage.getTools().filter { !it.isExecuted }

            if (toolCalls.isEmpty()) {
                // 没有工具调用，生成完成
                // 设置 Reasoning 的 finishedAt，否则UI会一直显示"思考中"
                val now = kotlin.time.Clock.System.now()
                val finalMessage = processedMessage.copy(
                    parts = processedMessage.parts.map { part ->
                        if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                            part.copy(finishedAt = now)
                        } else {
                            part
                        }
                    }
                )
                messages[messages.lastIndex] = finalMessage
                // 最终更新 session 状态（用 id 匹配就地更新）
                updateOrAppendAiMessage(conversationId, finalMessage)
                break
            }

            // 有工具调用
            hasToolCalls = true
            Log.d(TAG, "Tool calls detected: ${toolCalls.size}")

            // 执行工具（后台模式下自动执行，不需要用户审批）
            val executedTools = mutableListOf<UIMessagePart.Tool>()
            for (toolCall in toolCalls) {
                val toolDef = tools.find { it.name == toolCall.toolName }
                if (toolDef == null) {
                    Log.w(TAG, "Tool ${toolCall.toolName} not found")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool not found"}"""))
                    ))
                    continue
                }

                // 检查是否需要审批
                if (toolDef.needsApproval && toolDef.name != "get_app_usage") {
                    // 后台模式下，需要审批的工具自动拒绝
                    Log.w(TAG, "Tool ${toolCall.toolName} needs approval, auto-denying in proactive mode")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool execution denied: requires user approval in proactive mode"}""")),
                        approvalState = ToolApprovalState.Denied("Proactive mode: requires approval")
                    ))
                } else {
                    // 执行工具
                    try {
                        val args = try {
                            json.parseToJsonElement(toolCall.input.ifBlank { "{}" })
                        } catch (e: Exception) {
                            // toolCall.input 可能因为流式截断而是不完整的 JSON, 回退为空对象
                            Log.w(TAG, "Tool ${toolCall.toolName} input JSON is incomplete, falling back to empty object: ${toolCall.input.take(200)}")
                            JsonObject(emptyMap())
                        }
                        Log.d(TAG, "Executing tool ${toolDef.name} with args: $args")
                        val result = toolDef.execute(args)
                        executedTools.add(toolCall.copy(output = result))
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool execution failed: ${toolCall.toolName}, args=${toolCall.input}", e)
                        executedTools.add(toolCall.copy(
                            output = listOf(UIMessagePart.Text("""{"error":"${e.message}"}"""))
                        ))
                    }
                }
            }

            // 更新消息中的工具状态
            val updatedParts = processedMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else {
                    part
                }
            }
            val updatedMessage = processedMessage.copy(parts = updatedParts)
            messages[messages.lastIndex] = updatedMessage
            // 更新 session 状态（带工具结果的消息，用 id 匹配就地更新）
            updateOrAppendAiMessage(conversationId, updatedMessage)
        }

        return Triple(messages, hasToolCalls, hasJumpFlag)
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null
}
