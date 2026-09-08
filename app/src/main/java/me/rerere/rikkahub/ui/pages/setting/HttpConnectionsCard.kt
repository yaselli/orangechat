package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.common.network.CleartextPolicy
import me.rerere.common.network.HttpAccess

@Composable
internal fun HttpConnectionsCard() {
    val allowed by HttpAccess.allowed.collectAsStateWithLifecycle()
    val blocked by HttpAccess.blocked.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("HTTP 连接")
            Text("默认使用加密连接；已允许 ${allowed.size} 个 HTTP 地址，最近拦截 ${blocked.size} 个地址。")
            TextButton(onClick = { open = true }) { Text("管理连接许可") }
        }
    }
    if (open && candidate == null) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("HTTP 连接许可") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("模型、语音、MCP、插件 fetch 和原生下载使用此许可。本机 localhost、127.0.0.1、::1 无需添加。网页脚本、终端和原生媒体库不受此设置限制。")
                    Text("升级前使用的 HTTP 接口也需确认一次，允许后请重试。许可只适用于显示的地址和端口，不包含其子域名。")
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("地址，例如 http://192.168.1.2:8080") },
                        singleLine = true,
                    )
                    TextButton(
                        enabled = CleartextPolicy.cleartextOrigin(input.trim()) != null,
                        onClick = { candidate = CleartextPolicy.cleartextOrigin(input.trim()) },
                    ) { Text("添加许可") }
                    if (blocked.isNotEmpty()) Text("最近拦截（仅本次运行）")
                    blocked.sorted().forEach { origin ->
                        Text(origin)
                        TextButton(onClick = { candidate = origin }) { Text("确认此地址") }
                    }
                    if (allowed.isNotEmpty()) Text("已允许")
                    allowed.sorted().forEach { origin ->
                        Text(origin)
                        TextButton(onClick = { HttpAccess.revoke(origin) }) { Text("撤销许可") }
                    }
                    Text("撤销会阻止后续请求；已经建立的流式连接不会立即断开。许可仅保存在本机。")
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("完成") } },
        )
    }
    candidate?.let { origin ->
        AlertDialog(
            onDismissRequest = { candidate = null },
            title = { Text("允许未加密连接？") },
            text = { Text("$origin\n\nHTTP 不加密传输。密钥、聊天和文件等内容可能被网络中的他人读取或修改。若服务支持 HTTPS，请优先使用 HTTPS。确认后，受此设置管理的功能都可连接到这个地址和端口。") },
            confirmButton = {
                TextButton(onClick = {
                    HttpAccess.allow(origin)
                    candidate = null
                    input = ""
                }) { Text("了解风险，允许此地址") }
            },
            dismissButton = { TextButton(onClick = { candidate = null }) { Text("取消") } },
        )
    }
}
