package com.mhamad.nexora.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.core.ReasoningLevel
import com.mhamad.nexora.AppScope
import com.mhamad.nexora.data.datastore.migration.PreferenceStoreV1Migration
import com.mhamad.nexora.data.datastore.migration.PreferenceStoreV2Migration
import com.mhamad.nexora.data.datastore.migration.PreferenceStoreV3Migration
import com.mhamad.nexora.data.datastore.migration.PreferenceStoreV4Migration
import com.mhamad.nexora.data.model.Assistant
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

private const val SETTINGS_STORE_NAME = "settings"

private const val READ_MAX_RETRIES = 3

@Volatile
private var settingsDataStore: DataStore<Preferences>? = null

private val Context.settingsStore: DataStore<Preferences>
    get() = settingsDataStore ?: synchronized(SettingsStore::class) {
        settingsDataStore ?: createSettingsDataStore(applicationContext).also { settingsDataStore = it }
    }

private fun createSettingsDataStore(context: Context): DataStore<Preferences> {
    val file = context.preferencesDataStoreFile(SETTINGS_STORE_NAME)
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { exception ->
            Log.e(TAG, "Settings datastore corrupted, resetting", exception)
            runCatching {
                file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }.onFailure {
                Log.e(TAG, "Failed to backup corrupted settings file", it)
            }
            emptyPreferences()
        },
        migrations = listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            PreferenceStoreV4Migration(),
        ),
        produceFile = { file },
    )
}

/**
 *
 */
class SettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    constructor(context: Context, scope: AppScope) : this(context.settingsStore, scope)

    companion object {
        val VERSION = intPreferencesKey("data_version")

        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val ENABLE_AUTO_TITLE = booleanPreferencesKey("enable_auto_title")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val TRANSLATE_TARGET_LANGUAGE = stringPreferencesKey("translate_target_language")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")

        val PROVIDERS = stringPreferencesKey("providers")

        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")
        val UPLOAD_S3_CONFIG = stringPreferencesKey("upload_s3_config")

        val MEDIA_GENERATION_PROVIDERS = stringPreferencesKey("media_generation_providers")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

        // Uses the same DataStore singleton without starting settings flows or requiring Koin.
        internal suspend fun restoreBeforeInitialization(context: Context, settings: Settings, launchCount: Int) {
            require(!settings.init) { "Cannot restore uninitialized settings" }
            context.settingsStore.edit { preferences ->
                preferences[LAUNCH_COUNT] = launchCount
                preferences.putSettings(settings.normalized())
            }
        }
    }

    private val preferencesFlow = dataStore.data
        .retryWhen { cause, attempt ->
            val shouldRetry = cause is IOException && cause !is CorruptionException && attempt < READ_MAX_RETRIES
            if (shouldRetry) {
                Log.w(TAG, "Failed to read settings, retrying (${attempt + 1}/$READ_MAX_RETRIES)", cause)
                delay((100L shl attempt.toInt()).milliseconds)
            }
            shouldRetry
        }

    private val state = MutableStateFlow(Settings.dummy())

    val settingsFlow: StateFlow<Settings> = state.asStateFlow()

    suspend fun awaitLoaded(): Settings = state.first { !it.init }

    private val stateLock = Any()

    private var persisted: Settings? = null
    private val persistMutex = Mutex()

    init {
        scope.launch(Dispatchers.Default) {
            state.value = try {
                preferencesFlow.first().toSettings().normalized()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load settings", e)
                Runtime.getRuntime().halt(1)
                throw e
            }
        }
    }

    val launchCountFlow: Flow<Int> = preferencesFlow
        .map { preferences -> preferences[LAUNCH_COUNT] ?: 0 }
        .distinctUntilChanged()

    /**
     *
     *
     */
    suspend fun update(fn: (Settings) -> Settings) {
        if (state.value.init) awaitLoaded()
        synchronized(stateLock) {
            val updated = fn(state.value)
            if (updated.init) {
                Log.w(TAG, "Cannot update dummy settings")
                return
            }
            val normalized = updated.normalized()
            state.value = if (normalized == updated) updated else normalized
        }
        persist()
    }

    private suspend fun persist() {
        withContext(NonCancellable + Dispatchers.Default) {
            persistMutex.withLock {
                val latest = state.value
                val base = persisted
                if (latest !== base) {
                    dataStore.edit { preferences -> preferences.putSettings(latest, base) }
                    persisted = latest
                }
            }
        }
    }

    suspend fun incrementLaunchCount(): Int {
        var count = 0
        dataStore.edit { preferences ->
            count = (preferences[LAUNCH_COUNT] ?: 0) + 1
            preferences[LAUNCH_COUNT] = count
        }
        return count
    }

    suspend fun setLaunchCount(count: Int) {
        dataStore.edit { preferences -> preferences[LAUNCH_COUNT] = count }
    }

    suspend fun selectAssistant(assistantId: Uuid) {
        update { it.copy(assistantId = assistantId) }
    }

    private suspend fun updateAssistant(assistantId: Uuid, transform: (Assistant) -> Assistant) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) transform(assistant) else assistant
                }
            )
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        updateAssistant(assistantId) { it.copy(chatModelId = modelId) }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        updateAssistant(assistantId) { it.copy(reasoningLevel = reasoningLevel) }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        updateAssistant(assistantId) { it.copy(enableWebSearch = enabled) }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        updateAssistant(assistantId) { it.copy(mcpServers = mcpServers) }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        updateAssistant(assistantId) {
            it.copy(
                modeInjectionIds = modeInjectionIds,
                lorebookIds = lorebookIds,
                quickMessageIds = quickMessageIds,
            )
        }
    }
}
