package me.rerere.rikkahub.utils

internal data class UpdateApkIdentity(
    val packageName: String,
    val version: String?,
    val code: Long,
    val debuggable: Boolean,
    val signers: Set<String>,
)

internal fun validateUpdateApkIdentity(
    installed: UpdateApkIdentity,
    archive: UpdateApkIdentity,
    update: UpdateInfo,
) {
    if (
        archive.packageName != installed.packageName ||
            archive.debuggable != installed.debuggable ||
            archive.version != update.version ||
            archive.code != update.versionCode.toLong() ||
            archive.code <= installed.code ||
            archive.signers.isEmpty() ||
            archive.signers != installed.signers
    ) {
        throw InvalidUpdateException("安装包的版本、渠道或签名不匹配，请重新检查更新")
    }
}
