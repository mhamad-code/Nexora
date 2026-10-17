package com.mhamad.nexora.ui.pages.mediacreation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.mhamad.nexora.data.model.MediaCreationSession
import com.mhamad.nexora.data.repository.MediaCreationRepository
import com.mhamad.nexora.service.MediaCreationService
import java.io.File
import kotlin.uuid.Uuid

class MediaCreationSessionsVM(
    private val repository: MediaCreationRepository,
    private val service: MediaCreationService,
) : ViewModel() {
    val sessions: StateFlow<List<MediaCreationSession>?> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun newSession(onReady: (Uuid) -> Unit) {
        viewModelScope.launch { onReady(repository.newSession().id) }
    }

    fun resolve(path: String): File = repository.resolve(path)

    fun rename(id: Uuid, title: String) {
        viewModelScope.launch { repository.renameSession(id, title.trim()) }
    }

    fun delete(id: Uuid) {
        viewModelScope.launch { service.deleteSession(id) }
    }
}
