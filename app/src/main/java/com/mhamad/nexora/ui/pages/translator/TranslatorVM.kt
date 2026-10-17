package com.mhamad.nexora.ui.pages.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.mhamad.nexora.data.ai.TranslationHandler
import com.mhamad.nexora.data.datastore.Settings
import com.mhamad.nexora.data.datastore.SettingsStore
import com.mhamad.nexora.data.db.entity.TranslationHistoryEntity
import com.mhamad.nexora.data.repository.TranslationHistoryRepository
import java.util.Locale

private const val TAG = "TranslatorVM"

class TranslatorVM(
    private val settingsStore: SettingsStore,
    private val translationHandler: TranslationHandler,
    private val historyRepository: TranslationHistoryRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    private val _translating = MutableStateFlow(false)
    val translating: StateFlow<Boolean> = _translating

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText

    val targetLanguage: StateFlow<Locale> = settings
        .map { Locale.forLanguageTag(it.translateTargetLanguage) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Locale.SIMPLIFIED_CHINESE)

    val history: Flow<PagingData<TranslationHistoryEntity>> = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { historyRepository.pagingAll() },
    ).flow.cachedIn(viewModelScope)

    val errorFlow = MutableSharedFlow<Throwable>()

    private var currentJob: Job? = null

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun updateTargetLanguage(language: Locale) {
        updateSettings { it.copy(translateTargetLanguage = language.toLanguageTag()) }
    }

    fun useResultAsInput() {
        _inputText.value = _translatedText.value
        _translatedText.value = ""
    }

    fun restoreHistory(entry: TranslationHistoryEntity) {
        cancelTranslation()
        _inputText.value = entry.sourceText
        _translatedText.value = entry.translatedText
        updateSettings { it.copy(translateTargetLanguage = entry.targetLanguage) }
    }

    fun deleteHistory(entry: TranslationHistoryEntity) {
        viewModelScope.launch { historyRepository.deleteById(entry.id) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyRepository.deleteAll() }
    }

    fun translate() {
        val inputText = _inputText.value
        if (inputText.isBlank()) return

        currentJob?.cancel()

        _translating.value = true
        _translatedText.value = ""

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val settings = settings.value
                val targetLanguage = targetLanguage.value
                translationHandler.translateText(
                    settings = settings,
                    sourceText = inputText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    _translatedText.value = translatedText
                }.collect { /* النتيجة موجودة بالفعل في onStreamUpdate التحديث داخل */ }

                val result = _translatedText.value
                if (result.isNotBlank()) {
                    historyRepository.add(
                        sourceText = inputText,
                        translatedText = result,
                        targetLanguage = targetLanguage.toLanguageTag(),
                        modelId = settings.translateModeId,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                errorFlow.emit(e)
            } finally {
                if (currentJob == coroutineContext[Job]) _translating.value = false
            }
        }
        currentJob = job
        job.start()
    }

    fun cancelTranslation() {
        currentJob?.cancel()
        currentJob = null
        _translating.value = false
    }
}
