package me.rerere.rikkahub.data.memory

import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Never display raw provider error bodies: they may echo keys, request headers or memory text. */
internal fun memoryIndexFailureReason(failure: Throwable): String {
    val causes = generateSequence(failure) { it.cause }.take(8).toList()
    val knownLocalErrors = setOf(
        "请先选择 OpenAI 兼容接口的嵌入模型", "已有索引任务正在运行", "待处理记忆已变化，请重新确认索引调用次数",
        "嵌入接口返回的数量不匹配", "嵌入接口返回了无效向量",
    )
    causes.firstNotNullOfOrNull { it.message?.takeIf(knownLocalErrors::contains) }?.let { return it }
    val http = causes.firstNotNullOfOrNull {
        Regex("^Failed to generate embedding: ([1-5][0-9]{2})(?:\\s|$)").find(it.message.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
    }
    if (http != null) {
        val reason = when (http) {
            400, 422 -> "接口不接受当前模型或请求参数，请核对模型 ID 和嵌入接口支持情况"
            401 -> "接口认证失败，请检查 API Key"
            403 -> "接口拒绝访问，请检查账号、模型权限或服务商限制"
            404 -> "未找到嵌入接口或模型，请检查 API 地址与模型 ID"
            413 -> "接口拒绝了过大的批量请求"
            429 -> "接口限流或额度不足，请查看服务商控制台"
            in 500..599 -> "服务商接口暂时异常"
            else -> "嵌入接口拒绝了请求"
        }
        return "$reason（HTTP $http）"
    }
    return when {
        causes.any { it is UnknownHostException } -> "无法解析接口域名，请检查 API 地址和网络"
        causes.any { it is InterruptedIOException } -> "嵌入请求超时，手动索引单次最多等待 60 秒"
        causes.any { it is SSLException } -> "接口 TLS／证书连接失败，请检查网络或接口地址"
        causes.any { it is IOException } -> "嵌入请求的网络连接失败或中断"
        causes.any { it.javaClass.name.contains("sqlite", ignoreCase = true) } -> "本地索引数据库读写失败"
        causes.any { it is SerializationException || it is NumberFormatException || it.message in setOf(
            "No data in response", "Missing embedding index", "Missing embedding vector",
            "Invalid embedding indices", "Invalid embedding vectors", "Embedding response count mismatch",
        ) } -> "接口返回格式不符合嵌入协议，缺少有效的 data／index／embedding 向量"
        else -> "本地索引处理失败（${failure.javaClass.simpleName}），请反馈这条提示"
    }
}
