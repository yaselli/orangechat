/*
 * 橘瓣 OrangeChat，基于 RikkaHub；GNU AGPL v3，见 LICENSE。
 */
package me.rerere.rikkahub.ui.pages.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import me.rerere.rikkahub.data.repository.TranslationHistoryRepository
import java.util.Locale

class TranslatorVM(
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
    private val historyRepository: TranslationHistoryRepository,
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())
    private val _translating = MutableStateFlow(false)
    val translating = _translating.asStateFlow()
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()
    private val _translatedText = MutableStateFlow("")
    val translatedText = _translatedText.asStateFlow()
    val targetLanguage = settings.map { Locale.forLanguageTag(it.translateTargetLanguage) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Locale.SIMPLIFIED_CHINESE)
    val history = Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
        historyRepository.pagingAll()
    }.flow.cachedIn(viewModelScope)
    val errorFlow = MutableSharedFlow<Throwable>()
    private var currentJob: Job? = null

    fun updateSettings(settings: Settings) {
        val previous = this.settings.value
        viewModelScope.launch { settingsStore.updateFrom(previous, settings) }
    }

    fun updateInputText(text: String) { _inputText.value = text }

    fun updateTargetLanguage(language: Locale) {
        viewModelScope.launch { settingsStore.update { it.copy(translateTargetLanguage = language.toLanguageTag()) } }
    }

    fun useResultAsInput() {
        cancelTranslation()
        _inputText.value = _translatedText.value
        _translatedText.value = ""
    }

    fun restoreHistory(entry: TranslationHistoryEntity) {
        cancelTranslation()
        _inputText.value = entry.sourceText
        _translatedText.value = entry.translatedText
        updateTargetLanguage(Locale.forLanguageTag(entry.targetLanguage))
    }

    fun deleteHistory(entry: TranslationHistoryEntity) = editHistory { historyRepository.deleteById(entry.id) }
    fun clearHistory() = editHistory { historyRepository.deleteAll() }

    private fun editHistory(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { errorFlow.emit(error) }
        }
    }

    fun translate() {
        val source = _inputText.value
        if (source.isBlank()) return
        val language = targetLanguage.value
        currentJob?.cancel()
        _translating.value = true
        _translatedText.value = ""
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val requestSettings = settingsStore.settingsFlowRaw.first()
                var result = ""
                generationHandler.translateText(requestSettings, source, language) { text ->
                    result = text
                    if (currentJob == coroutineContext[Job]) _translatedText.value = text
                }.collect { }
                ensureActive()
                if (result.isNotBlank()) historyRepository.add(
                    source, result, language.toLanguageTag(), requestSettings.translateModeId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorFlow.emit(error)
            } finally {
                if (currentJob == coroutineContext[Job]) {
                    _translating.value = false
                    currentJob = null
                }
            }
        }
        currentJob = job
        job.start()
    }

    fun cancelTranslation() {
        val previous = currentJob
        currentJob = null
        previous?.cancel()
        _translating.value = false
    }
}
