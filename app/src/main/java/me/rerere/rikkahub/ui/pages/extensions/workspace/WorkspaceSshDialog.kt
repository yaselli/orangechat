package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkahub.ui.theme.MaterialAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.SshWorkspaceDraft
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshHostKey
import me.rerere.workspace.ssh.SshHostKeyChangedException
import me.rerere.workspace.ssh.SshUnknownHostKeyException
import org.koin.compose.koinInject

/** Credentials are transient Compose state, never placed in saved instance state or log messages. */
@Composable
internal fun WorkspaceSshDialog(
    existing: WorkspaceEntity?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val repository: WorkspaceRepository = koinInject()
    val scope = rememberCoroutineScope()
    val previous = remember(existing?.id, existing?.sshConfig) { existing?.sshSettings() }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(previous?.host.orEmpty()) }
    var port by remember { mutableStateOf((previous?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(previous?.username.orEmpty()) }
    var directory by remember { mutableStateOf(previous?.remoteDir ?: "/home/") }
    var auth by remember { mutableStateOf(previous?.authType ?: SshAuthType.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var trustedKey by remember { mutableStateOf(previous?.hostKey) }
    var pendingKey by remember { mutableStateOf<SshHostKey?>(null) }
    var changedKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var saveAfterTrust by remember { mutableStateOf(false) }

    fun check(save: Boolean) {
        if (busy) return
        val parsedPort = port.toIntOrNull()
        if (parsedPort == null || parsedPort !in 1..65535 || host.isBlank() || user.isBlank()) {
            status = "请检查服务器地址、端口和用户名"
            return
        }
        val draft = SshWorkspaceDraft(name, host, parsedPort, user, auth, password, privateKey,
            passphrase, directory, trustedKey)
        busy = true
        status = "正在连接…"
        scope.launch {
            try {
                val result = repository.probeSsh(existing?.id, draft)
                status = "连接成功；远程目录" + if (result.remoteDirExists) "已存在" else "将在首次使用时创建"
                if (save) {
                    repository.saveSsh(existing?.id, draft)
                    onSaved()
                }
            } catch (e: SshUnknownHostKeyException) {
                pendingKey = e.hostKey
                changedKey = false
                saveAfterTrust = save
                status = null
            } catch (e: SshHostKeyChangedException) {
                pendingKey = e.actual
                changedKey = true
                saveAfterTrust = save
                status = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = e.message ?: "SSH 连接失败，请检查配置"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing == null) "添加远程 SSH 工作区" else "编辑远程 SSH 工作区") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("文件与命令会在远程服务器上执行。登录凭据仅在本机加密保存。")
                OutlinedTextField(name, { name = it }, label = { Text("工作区名称") }, enabled = !busy)
                OutlinedTextField(host, { host = it; trustedKey = null }, label = { Text("服务器地址") }, enabled = !busy)
                OutlinedTextField(port, { port = it; trustedKey = null }, label = { Text("端口") }, enabled = !busy)
                OutlinedTextField(user, { user = it }, label = { Text("用户名") }, enabled = !busy)
                OutlinedTextField(directory, { directory = it }, label = { Text("远程工作目录（绝对路径）") }, enabled = !busy)
                Row {
                    TextButton(enabled = !busy, onClick = { auth = SshAuthType.PASSWORD }) {
                        Text(if (auth == SshAuthType.PASSWORD) "✓ 密码" else "密码")
                    }
                    TextButton(enabled = !busy, onClick = { auth = SshAuthType.PRIVATE_KEY }) {
                        Text(if (auth == SshAuthType.PRIVATE_KEY) "✓ 私钥" else "私钥")
                    }
                }
                if (auth == SshAuthType.PASSWORD) {
                    OutlinedTextField(password, { password = it }, label = { Text("密码") },
                        supportingText = { if (previous?.encryptedPassword != null) Text("留空沿用已保存的密码") },
                        visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                } else {
                    OutlinedTextField(privateKey, { privateKey = it }, label = { Text("私钥（OpenSSH / PEM）") },
                        supportingText = { if (previous?.encryptedPrivateKey != null) Text("留空沿用已保存的私钥") },
                        maxLines = 4, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                    OutlinedTextField(passphrase, { passphrase = it }, label = { Text("私钥口令，可留空") },
                        visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                }
                trustedKey?.let { Text("服务器指纹：${it.fingerprint}", style = MaterialTheme.typography.bodySmall) }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(enabled = !busy, onClick = { check(false) }) { Text("测试连接") }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { check(true) }) { Text("测试并保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
    pendingKey?.let { key ->
        AlertDialog(
            onDismissRequest = { pendingKey = null },
            title = { Text(if (changedKey) "服务器指纹已改变" else "确认服务器指纹") },
            text = { Text(buildString {
                if (changedKey) appendLine("此服务器与之前保存的指纹不一致，请核对后再继续。")
                else appendLine("首次连接，请核对服务器管理员提供的指纹。")
                trustedKey?.takeIf { changedKey }?.let { appendLine("原指纹：${it.fingerprint}") }
                append("${key.type}\n${key.fingerprint}")
            }) },
            confirmButton = { TextButton(onClick = {
                trustedKey = key
                pendingKey = null
                check(saveAfterTrust)
            }) { Text("信任并继续") } },
            dismissButton = { TextButton(onClick = { pendingKey = null }) { Text("取消") } },
        )
    }
}
