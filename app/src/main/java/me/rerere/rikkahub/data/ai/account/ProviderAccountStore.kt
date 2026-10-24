package me.rerere.rikkahub.data.ai.account

import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

interface ProviderAccountStore {
    suspend fun findAccount(id: Uuid): ProviderSetting.Account?
    suspend fun updateAccounts(transform: (List<ProviderSetting>) -> List<ProviderSetting>)
}

class SettingsAccountStore(private val settings: SettingsStore) : ProviderAccountStore {
    override suspend fun findAccount(id: Uuid): ProviderSetting.Account? =
        settings.settingsFlowRaw.first().providers.find { it.id == id } as? ProviderSetting.Account

    override suspend fun updateAccounts(transform: (List<ProviderSetting>) -> List<ProviderSetting>) {
        settings.updateAccountCredentials { old -> old.copy(providers = transform(old.providers)) }
    }
}

/** Normal edits preserve the latest login session, even if their UI snapshot predates a refresh/logout. */
internal fun preserveAccountCredentials(
    desired: List<ProviderSetting>,
    current: List<ProviderSetting>,
): List<ProviderSetting> = desired.map { provider ->
    val previous = current.find { it.id == provider.id }
    val safeModels = provider.models.map { model ->
        model.copy(providerOverwrite = model.providerOverwrite?.withoutAccountCredentials())
    }
    val edited = provider.copyProvider(models = safeModels)
    if (edited is ProviderSetting.Account) {
        edited.withCredential((previous as? ProviderSetting.Account)?.takeIf { it::class == edited::class }?.credential)
    } else edited
}

/** Subscription sessions are local: omit them from provider sharing, web snapshots and cloud backup. */
fun ProviderSetting.withoutAccountCredentials(): ProviderSetting {
    val safe = copyProvider(models = models.map { model ->
        model.copy(providerOverwrite = model.providerOverwrite?.withoutAccountCredentials())
    })
    return if (safe is ProviderSetting.Account) safe.withCredential(null) else safe
}

fun me.rerere.rikkahub.data.datastore.Settings.withoutAccountCredentials():
    me.rerere.rikkahub.data.datastore.Settings = copy(providers = providers.map { it.withoutAccountCredentials() })
