package me.rerere.rikkahub.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import org.xmlpull.v1.XmlPullParser

@Suppress("DEPRECATION")
internal fun updateApkIdentity(info: PackageInfo, debuggable: Boolean? = null): UpdateApkIdentity {
    val signers =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    return UpdateApkIdentity(
        info.packageName,
        info.versionName,
        PackageInfoCompat.getLongVersionCode(info),
        debuggable ?: ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0),
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
private fun updateSigningFlags(): Int =
    if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else PackageManager.GET_SIGNATURES

/** Read the APK itself: some Android versions omit derived flags from archive ApplicationInfo. */
@Suppress("DEPRECATION")
internal fun updateApkArchiveIdentity(context: Context, file: File): UpdateApkIdentity {
    val manager = context.packageManager
    val archive =
        manager.getPackageArchiveInfo(file.path, updateSigningFlags())
            ?: throw InvalidUpdateException("无法读取安装包，请重新下载")
    val application =
        ApplicationInfo(archive.applicationInfo ?: throw InvalidUpdateException("安装包缺少应用信息，请重新下载"))
            .apply {
                sourceDir = file.path
                publicSourceDir = file.path
                splitSourceDirs = null
                splitPublicSourceDirs = null
            }
    val resources = manager.getResourcesForApplication(application)
    resources.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
        var packageMatches = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.name == "manifest") {
                    packageMatches =
                        parser.getAttributeValue(null, "package") == archive.packageName
                } else if (parser.name == "application" && packageMatches) {
                    val attributes =
                        resources.obtainAttributes(parser, intArrayOf(android.R.attr.debuggable))
                    try {
                        return updateApkIdentity(
                            archive,
                            debuggable = attributes.getBoolean(0, false),
                        )
                    } finally {
                        attributes.recycle()
                    }
                }
            }
            parser.next()
        }
    }
    throw InvalidUpdateException("无法读取安装包的渠道信息，请重新下载")
}

@Suppress("DEPRECATION")
internal fun verifyUpdateApk(context: Context, file: File, update: UpdateInfo) {
    verifyUpdateBytes(file, update.download)
    val installed = context.packageManager.getPackageInfo(context.packageName, updateSigningFlags())
    validateUpdateApkIdentity(
        updateApkIdentity(installed),
        updateApkArchiveIdentity(context, file),
        update,
    )
}
