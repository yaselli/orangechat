package me.rerere.rikkahub.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

class SafBackupFolder(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun grant(folder: Uri) {
        resolver.takePersistableUriPermission(
            folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        requireAccess(folder.toString())
    }

    fun requireAccess(folder: String) {
        val uri = Uri.parse(folder)
        check(DocumentsContract.isTreeUri(uri)) { "请选择文件夹" }
        check(resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }) {
            "备份文件夹授权已失效，请重新选择"
        }
    }

    fun create(folder: String): String {
        requireAccess(folder)
        val tree = Uri.parse(folder)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "Xiguaban-auto-${java.time.LocalDate.now()}-${java.util.UUID.randomUUID()}.zip"
        return (DocumentsContract.createDocument(resolver, parent, "application/zip", name)
            ?: throw IOException("不能在所选文件夹创建备份")).toString()
    }

    fun output(uri: String) = resolver.openOutputStream(Uri.parse(uri), "wt")
        ?: throw IOException("无法写入备份文件")

    fun input(uri: String) = resolver.openInputStream(Uri.parse(uri))
        ?: throw IOException("无法读回校验备份文件")

    fun delete(uri: String) {
        try {
            check(DocumentsContract.deleteDocument(resolver, Uri.parse(uri))) { "未能清理旧备份" }
        } catch (_: java.io.FileNotFoundException) {
            // Already deleted by the user; no longer needs cleanup.
        }
    }
}
