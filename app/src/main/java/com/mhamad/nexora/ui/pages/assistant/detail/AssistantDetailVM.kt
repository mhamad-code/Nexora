package com.mhamad.nexora.ui.pages.assistant.detail

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.mhamad.nexora.data.ai.MemoryConsolidationScheduler
import com.mhamad.nexora.data.ai.MemoryConsolidationStatus
import com.mhamad.nexora.data.datastore.Settings
import com.mhamad.nexora.data.datastore.SettingsStore
import com.mhamad.nexora.data.db.entity.WorkspaceEntity
import com.mhamad.nexora.data.files.FilesManager
import com.mhamad.nexora.data.skills.SkillManager
import com.mhamad.nexora.data.skills.SkillMetadata
import com.mhamad.nexora.data.model.Assistant
import com.mhamad.nexora.data.model.MemoryFile
import com.mhamad.nexora.data.model.Avatar
import com.mhamad.nexora.data.model.Tag
import com.mhamad.nexora.data.repository.MemoryRepository
import com.mhamad.nexora.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    val memories = assistant
        .flatMapLatest { currentAssistant ->
            memoryRepository.getFilesFlow(memoryIdOf(currentAssistant))
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val memoryConsolidation: StateFlow<MemoryConsolidationStatus> = assistant
        .flatMapLatest { currentAssistant ->
            memoryConsolidationScheduler.statusOf(memoryIdOf(currentAssistant))
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = MemoryConsolidationStatus()
        )

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        viewModelScope.launch {
            settingsStore.update { it.copy(assistantTags = tags) }
            update(
                assistant.value.copy(
                    tags = tagIds.toList()
                )
            )
            Log.d(TAG, "updateTags: ${tagIds.joinToString(",")}")
            cleanupUnusedTags()
        }
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val validTagIds = settings.assistantTags.map { it.id }.toSet()

                val cleanedAssistants = settings.assistants.map { assistant ->
                    val validTags = assistant.tags.filter { tagId ->
                        validTagIds.contains(tagId)
                    }
                    if (validTags.size != assistant.tags.size) {
                        assistant.copy(tags = validTags)
                    } else {
                        assistant
                    }
                }

                val usedTagIds = cleanedAssistants.flatMap { it.tags }.toSet()

                val cleanedTags = settings.assistantTags.filter { tag ->
                    usedTagIds.contains(tag.id)
                }

                val needUpdateAssistants = cleanedAssistants != settings.assistants
                val needUpdateTags = cleanedTags.size != settings.assistantTags.size

                if (needUpdateAssistants || needUpdateTags) {
                    settings.copy(
                        assistants = cleanedAssistants,
                        assistantTags = cleanedTags
                    )
                } else {
                    settings
                }
            }
        }
    }

    fun update(assistant: Assistant) {
        viewModelScope.launch {
            var replaced: Assistant? = null
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            replaced = it
                            assistant
                        } else {
                            it
                        }
                    })
            }
            replaced?.let { old ->
                checkAvatarDelete(old = old, new = assistant)
                checkBackgroundDelete(old = old, new = assistant)
            }
        }
    }

    private fun memoryIdOf(assistant: Assistant) = if (assistant.useGlobalMemory) {
        MemoryRepository.GLOBAL_MEMORY_ID
    } else {
        assistantId.toString()
    }

    val memoryId: String get() = memoryIdOf(assistant.value)

    fun organizeMemory() {
        memoryConsolidationScheduler.organize(memoryId)
    }

    fun deleteMemory(memory: MemoryFile) {
        viewModelScope.launch {
            memoryRepository.deleteFile(memoryIdOf(assistant.value), memory.path)
        }
    }

    fun checkAvatarDelete(old: Assistant, new: Assistant) {
        if (old.avatar is Avatar.Image && old.avatar != new.avatar) {
            filesManager.deleteChatFiles(listOf(old.avatar.url.toUri()))
        }
    }

    fun checkBackgroundDelete(old: Assistant, new: Assistant) {
        val oldBackground = old.background
        val newBackground = new.background

        if (oldBackground != null && oldBackground != newBackground) {
            try {
                val oldUri = oldBackground.toUri()
                if (oldUri.scheme == "content" || oldUri.scheme == "file") {
                    filesManager.deleteChatFiles(listOf(oldUri))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete background file: $oldBackground", e)
            }
        }
    }
}
