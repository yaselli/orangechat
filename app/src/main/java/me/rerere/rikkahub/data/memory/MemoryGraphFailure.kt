package me.rerere.rikkahub.data.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class MemoryGraphBuildStage { PREPARING, REQUEST, OUTPUT, SAVE }
internal enum class MemoryGraphBuildProblem {
    UNKNOWN, SETTINGS_CHANGED, DISABLED, MODEL_UNAVAILABLE, TEXT_MODEL_REQUIRED, SOURCE_CHANGED,
    TIMEOUT, EMPTY_OUTPUT, TRUNCATED, OVERSIZED, JSON_STRUCTURE, MEMORY_IDS, RELATION_FIELDS, QUOTE,
}

/** Only these local codes are displayed. Provider bodies and serializer snippets can contain secrets. */
internal class MemoryGraphBuildFailure(
    val stage: MemoryGraphBuildStage,
    val problem: MemoryGraphBuildProblem,
    cause: Throwable? = null,
) : IllegalStateException("${stage.name}/${problem.name}", cause)

internal fun requireMemoryGraphBuild(
    condition: Boolean, stage: MemoryGraphBuildStage, problem: MemoryGraphBuildProblem,
) {
    if (!condition) throw MemoryGraphBuildFailure(stage, problem)
}

internal suspend fun <T> memoryGraphBuildStage(stage: MemoryGraphBuildStage, block: suspend () -> T): T = try {
    block()
} catch (failure: MemoryGraphBuildFailure) {
    throw failure
} catch (timeout: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw MemoryGraphBuildFailure(stage, MemoryGraphBuildProblem.TIMEOUT, timeout)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw MemoryGraphBuildFailure(stage, MemoryGraphBuildProblem.UNKNOWN, failure)
}

internal fun checkMemoryGraphFinishReason(reason: String?) {
    requireMemoryGraphBuild(reason?.lowercase() !in setOf("length", "max_tokens"),
        MemoryGraphBuildStage.OUTPUT, MemoryGraphBuildProblem.TRUNCATED)
}

internal fun memoryGraphBuildFailureMessage(failure: Exception): String {
    val local = failure as? MemoryGraphBuildFailure
    val stage = local?.stage ?: MemoryGraphBuildStage.PREPARING
    val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
    val reason = when (local?.problem) {
        MemoryGraphBuildProblem.SETTINGS_CHANGED -> "助手设置已改变，请重新打开建图入口确认"
        MemoryGraphBuildProblem.DISABLED -> "记忆或图谱开关未开启"
        MemoryGraphBuildProblem.MODEL_UNAVAILABLE -> "整理模型未选择、已删除或所属供应商不可用"
        MemoryGraphBuildProblem.TEXT_MODEL_REQUIRED -> "整理模型不是文字聊天模型"
        MemoryGraphBuildProblem.SOURCE_CHANGED -> "本批记忆在请求期间被修改或删除，已阻止旧结果写入"
        MemoryGraphBuildProblem.TIMEOUT -> "建图请求超时（传输上限60秒，任务上限65秒）"
        MemoryGraphBuildProblem.EMPTY_OUTPUT -> "模型没有返回可用的正文或结果为空"
        MemoryGraphBuildProblem.TRUNCATED -> "模型报告输出达到长度上限，结果可能被截断"
        MemoryGraphBuildProblem.OVERSIZED -> "模型返回的建图结果超过本地校验上限"
        MemoryGraphBuildProblem.JSON_STRUCTURE -> "模型结果不是要求的完整 JSON 结构，或必需字段缺失"
        MemoryGraphBuildProblem.MEMORY_IDS -> "模型返回的记忆编号缺失、重复或不属于本批"
        MemoryGraphBuildProblem.RELATION_FIELDS -> "关系字段不符合要求（实体类型、状态、别名数量或长度等）"
        MemoryGraphBuildProblem.QUOTE -> "依据摘录为空、过长或与对应记忆原文不一致"
        else -> {
            val http = if (stage == MemoryGraphBuildStage.REQUEST) causes.firstNotNullOfOrNull {
                Regex("^Failed to get response: ([1-5][0-9]{2})(?:\\s|$)").find(it.message.orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
            } else null
            when {
                http != null -> when (http) {
                    401 -> "接口认证失败，请检查供应商地址与 Key（HTTP 401）"
                    403 -> "接口拒绝访问，请检查模型权限（HTTP 403）"
                    404 -> "接口地址或模型未找到（HTTP 404）"
                    429 -> "接口限流或额度不足（HTTP 429）"
                    400, 413, 422 -> "接口不接受本次模型、参数或输入长度（HTTP $http）"
                    in 500..599 -> "供应商接口异常（HTTP $http）"
                    else -> "接口拒绝请求（HTTP $http）"
                }
                stage == MemoryGraphBuildStage.REQUEST && causes.any { it is UnknownHostException } -> "无法解析供应商域名，请检查网络和地址"
                stage == MemoryGraphBuildStage.REQUEST && causes.any { it is SSLException } -> "接口 TLS／证书连接失败"
                stage == MemoryGraphBuildStage.REQUEST && causes.any { it is InterruptedIOException } -> "建图网络请求超时"
                stage == MemoryGraphBuildStage.REQUEST && causes.any { it is IOException } -> "建图网络连接失败或中断"
                stage == MemoryGraphBuildStage.SAVE -> "本地数据库保存未正常完成"
                stage == MemoryGraphBuildStage.REQUEST -> "接口响应读取失败，尚未取得可校验的建图结果"
                stage == MemoryGraphBuildStage.OUTPUT -> "模型结果处理失败，尚未通过本地校验"
                else -> "本地准备或读取失败，请反馈此阶段提示"
            }
        }
    }
    val label = when (stage) {
        MemoryGraphBuildStage.PREPARING -> "准备阶段"
        MemoryGraphBuildStage.REQUEST -> "接口请求阶段"
        MemoryGraphBuildStage.OUTPUT -> "结果校验阶段"
        MemoryGraphBuildStage.SAVE -> "本地保存阶段"
    }
    val outcome = when (stage) {
        MemoryGraphBuildStage.PREPARING -> "尚未发起模型请求。"
        MemoryGraphBuildStage.REQUEST, MemoryGraphBuildStage.OUTPUT -> "本批未写入图谱；接口请求可能已计费。"
        MemoryGraphBuildStage.SAVE -> "未确认本批保存成功；接口请求可能已计费。"
    }
    return "建图失败 · $label：$reason。$outcome 不自动重试。"
}

internal data class MemoryGraphBuildFeedback(val message: String, val status: Pair<Int, Int>? = null)

/** A failed statistics refresh cannot reclassify a successfully committed graph as an unsaved batch. */
internal suspend fun memoryGraphBuildFeedback(
    build: suspend () -> Int, refresh: suspend () -> Pair<Int, Int>,
): MemoryGraphBuildFeedback {
    val added = try {
        build()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) {
        return MemoryGraphBuildFeedback(memoryGraphBuildFailureMessage(failure))
    }
    val success = "本次处理完成，新增 $added 条关系。"
    return try {
        MemoryGraphBuildFeedback(success, refresh())
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) {
        MemoryGraphBuildFeedback(success + "统计刷新失败，不表示本批未保存；请重开页面查看，不要因此重复建图。")
    }
}
