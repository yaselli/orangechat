package me.rerere.rikkahub.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

@Suppress("DEPRECATION")
internal fun updateApkIdentity(info: PackageInfo): UpdateApkIdentity {
    val signers =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    return UpdateApkIdentity(
        info.packageName,
        info.versionName,
        PackageInfoCompat.getLongVersionCode(info),
        (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        signers
            .orEmpty()
            .map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(
                    ""
                ) {
                    "%02x".format(it)
                }
            }
            .toSet(),
    )
}

@Suppress("DEPRECATION")
internal fun verifyUpdateApk(context: Context, file: File, update: UpdateInfo) {
    verifyUpdateBytes(file, update.download)
    val flags =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
    val installed = context.packageManager.getPackageInfo(context.packageName, flags)
    val archive =
        context.packageManager.getPackageArchiveInfo(file.path, flags)
            ?: throw InvalidUpdateException("无法读取安装包，请重新下载")
    validateUpdateApkIdentity(updateApkIdentity(installed), updateApkIdentity(archive), update)
}
