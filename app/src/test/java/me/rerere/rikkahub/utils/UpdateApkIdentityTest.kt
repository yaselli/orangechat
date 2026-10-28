package me.rerere.rikkahub.utils

import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateApkIdentityTest {
    private val installed =
        UpdateApkIdentity("me.rerere.rikkahub.debug", "2.3.4-debug.1", 169, true, setOf("key"))
    private val update =
        UpdateInfo(
            "2.3.5-debug.1",
            170,
            "Changes",
            UpdateDownload("unused", "unused", "1 MB"),
            "unused",
        )
    private val archive =
        installed.copy(version = update.version, code = update.versionCode.toLong())

    @Test
    fun `newer APK with matching channel package and signers is accepted`() {
        validateUpdateApkIdentity(installed, archive, update)
    }

    @Test
    fun `another package or channel is rejected`() {
        for (bad in
            listOf(archive.copy(packageName = "other.app"), archive.copy(debuggable = false))) {
            assertThrows(InvalidUpdateException::class.java) {
                validateUpdateApkIdentity(installed, bad, update)
            }
        }
    }

    @Test
    fun `version mismatch or downgrade is rejected`() {
        for (bad in
            listOf(
                archive.copy(version = "other"),
                archive.copy(code = 171),
                archive.copy(code = 168),
            )) {
            assertThrows(InvalidUpdateException::class.java) {
                validateUpdateApkIdentity(installed, bad, update)
            }
        }
        assertThrows(InvalidUpdateException::class.java) {
            validateUpdateApkIdentity(
                installed,
                installed,
                update.copy(version = installed.version!!, versionCode = 169),
            )
        }
    }

    @Test
    fun `missing wrong or partly overlapping certificates are rejected`() {
        for (signers in listOf(emptySet(), setOf("other"), setOf("key", "other"))) {
            assertThrows(InvalidUpdateException::class.java) {
                validateUpdateApkIdentity(installed, archive.copy(signers = signers), update)
            }
        }
    }
}
