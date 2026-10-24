package me.rerere.rikkahub.data.ai.account

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AccountCredentialIsolationTest {
    private val old = OAuthCredential("old-access", "old-refresh")
    private val fresh = OAuthCredential("new-access", "new-refresh")
    private val account = ProviderSetting.ChatGPT(credential = old)

    @Test
    fun staleEditsKeepRefreshedCredentialAndCannotResurrectLogout() {
        val edit = account.copy(name = "新名称", enabled = false)
        val saved = preserveAccountCredentials(listOf(edit), listOf(account.copy(credential = fresh)))
            .single() as ProviderSetting.ChatGPT
        assertEquals("新名称", saved.name)
        assertFalse(saved.enabled)
        assertEquals(fresh, saved.credential)
        val signedOut = preserveAccountCredentials(listOf(edit), listOf(account.copy(credential = null)))
            .single() as ProviderSetting.ChatGPT
        assertNull(signedOut.credential)
    }

    @Test
    fun importedCredentialsAndChangedAccountTypesDoNotAcquireExistingSession() {
        assertNull((preserveAccountCredentials(listOf(account), emptyList()).single() as ProviderSetting.Account).credential)
        val grok = ProviderSetting.Grok(id = account.id, credential = old)
        assertNull((preserveAccountCredentials(listOf(grok), listOf(account)).single() as ProviderSetting.Account).credential)
        assertNull((account.copyProvider(id = Uuid.random()) as ProviderSetting.Account).credential)
    }

    @Test
    fun sharingAndBackupStripNestedSessionsWithoutChangingModelMetadataOrLiveAccount() {
        val provider = ProviderSetting.OpenAI(models = listOf(Model(modelId = "nested", providerOverwrite = account)))
        val safe = provider.withoutAccountCredentials()
        val serialized = JsonInstant.encodeToString(ProviderSetting.serializer(), safe)
        assertFalse(serialized.contains(old.accessToken))
        assertFalse(serialized.contains(old.refreshToken!!))
        assertTrue(serialized.contains("nested"))
        assertNull((safe.models.single().providerOverwrite as ProviderSetting.Account).credential)
        assertEquals(old, account.credential)
        assertFalse(old.toString().contains(old.accessToken))
    }
}
