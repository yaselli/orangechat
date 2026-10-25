package me.rerere.rikkahub.ui.components.ui

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.utils.ProjectLinks
import me.rerere.rikkahub.utils.writeClipboardText

@Composable
internal fun ProjectCommunityDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("西瓜瓣交流群") },
        text = { Text("QQ群：${ProjectLinks.QQ_GROUP}\n复制群号后，可在 QQ 中搜索并申请加入。") },
        confirmButton = {
            TextButton(onClick = {
                context.writeClipboardText(ProjectLinks.QQ_GROUP)
                Toast.makeText(context, "已复制群号 ${ProjectLinks.QQ_GROUP}", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("复制群号") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
