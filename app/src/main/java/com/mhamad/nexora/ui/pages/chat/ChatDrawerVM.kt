package com.mhamad.nexora.ui.pages.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.mhamad.nexora.R
import com.mhamad.nexora.data.ai.MemoryConsolidationScheduler
import com.mhamad.nexora.data.datastore.ConversationSortOrder
import com.mhamad.nexora.data.datastore.SettingsStore
import com.mhamad.nexora.data.datastore.getCurrentAssistant
import com.mhamad.nexora.data.model.Conversation
import com.mhamad.nexora.data.model.Folder
import com.mhamad.nexora.data.repository.ConversationRepository
import com.mhamad.nexora.data.repository.FolderRepository
import com.mhamad.nexora.data.repository.memoryId
import com.mhamad.nexora.service.ChatService
import com.mhamad.nexora.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

class ChatDrawerVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val chatService: ChatService,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val assistantIdFlow = settingsStore.settingsFlow
        .map { it.assistantId }
        .distinctUntilChanged()

    private val sortOrderFlow = settingsStore.settingsFlow
        .map { it.displaySetting.conversationSortOrder }
        .distinctUntilChanged()

    private val _selectedFolderId = MutableStateFlow<Uuid?>(null)
    val selectedFolderId: StateFlow<Uuid?> = _selectedFolderId.asStateFlow()

    val folders: StateFlow<List<Folder>> = assistantIdFlow
        .flatMapLatest { folderRepo.getFoldersOfAssistant(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memoryDreaming: StateFlow<Boolean> = settingsStore.settingsFlow
        .map { settings -> settings.getCurrentAssistant().takeIf { it.enableMemory }?.memoryId }
        .distinctUntilChanged()
        .flatMapLatest { memoryId ->
            if (memoryId == null) flowOf(false) else memoryConsolidationScheduler.statusOf(memoryId).map { it.running }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(assistantIdFlow, _selectedFolderId, sortOrderFlow) { assistantId, folderId, sortOrder ->
            Triple(assistantId, folderId, sortOrder)
        }
            .flatMapLatest { (assistantId, folderId, sortOrder) ->
                val paging = if (folderId == null) {
                    conversationRepo.getUnfiledConversationsOfAssistantPaging(assistantId, sortOrder)
                } else {
                    conversationRepo.getConversationsOfFolderPaging(folderId, sortOrder)
                }
                paging.map { pagingData ->
                    pagingData
                        .map { ConversationListItem.Item(it) }
                        .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                            when {
                                after == null -> null
                                after.conversation.isPinned -> {
                                    if (before == null) ConversationListItem.PinnedHeader else null
                                }

                                else -> {
                                    val afterDate = after.conversation.sortDate(sortOrder)
                                    val sameGroup = before != null &&
                                        !before.conversation.isPinned &&
                                        before.conversation.sortDate(sortOrder) == afterDate
                                    if (sameGroup) {
                                        null
                                    } else {
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate),
                                            sortOrder = sortOrder,
                                        )
                                    }
                                }
                            }
                        }
                }
            }
            .cachedIn(viewModelScope)

    val scrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: 0
    val scrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    init {
        viewModelScope.launch {
            assistantIdFlow.collect {
                _selectedFolderId.value = null
            }
        }
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    fun selectFolder(folderId: Uuid?) {
        _selectedFolderId.value = folderId
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val assistantId = assistantIdFlow.first()
            folderRepo.createFolder(assistantId, trimmed)
        }
    }

    fun renameFolder(folderId: Uuid, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepo.renameFolder(folderId, trimmed)
        }
    }

    /**
     */
    fun deleteFolder(folderId: Uuid): Boolean {
        if (chatService.hasGeneratingConversationInFolder(folderId)) {
            return false
        }
        viewModelScope.launch {
            chatService.deleteFolder(folderId)
            if (_selectedFolderId.value == folderId) {
                _selectedFolderId.value = null
            }
        }
        return true
    }

    fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        viewModelScope.launch {
            chatService.moveConversationToFolder(conversationId, folderId)
        }
    }

    private fun Conversation.sortDate(sortOrder: ConversationSortOrder): LocalDate {
        val instant = when (sortOrder) {
            ConversationSortOrder.UPDATE_TIME -> updateAt
            ConversationSortOrder.CREATE_TIME -> createAt
        }
        return instant.atZone(ZoneId.systemDefault()).toLocalDate()
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}
