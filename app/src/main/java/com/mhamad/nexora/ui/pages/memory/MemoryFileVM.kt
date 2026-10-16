package com.mhamad.nexora.ui.pages.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.mhamad.nexora.data.model.MemoryFile
import com.mhamad.nexora.data.repository.MemoryRepository
import com.mhamad.nexora.data.repository.MemoryWriteResult

sealed interface MemoryFileState {
    data object Loading : MemoryFileState

    data object Missing : MemoryFileState

    data class Loaded(val file: MemoryFile) : MemoryFileState
}

/**
 *
 */
class MemoryFileVM(
    private val memoryId: String,
    path: String?,
    private val memoryRepository: MemoryRepository,
) : ViewModel() {
    private val normalizedPath = path?.let(MemoryFile::normalizePath)

    val state: StateFlow<MemoryFileState> =
        if (normalizedPath == null) {
            flowOf(MemoryFileState.Missing)
        } else {
            memoryRepository.getFilesFlow(memoryId).map { files ->
                files.find { it.path == normalizedPath }?.let(MemoryFileState::Loaded) ?: MemoryFileState.Missing
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MemoryFileState.Loading)

    fun create(path: String, content: String, onResult: (MemoryWriteResult) -> Unit) {
        viewModelScope.launch {
            onResult(memoryRepository.writeFile(memoryId, path, content, MemoryRepository.NEW_FILE_VERSION))
        }
    }

    fun save(content: String, onResult: (MemoryWriteResult) -> Unit) {
        val path = normalizedPath ?: return
        viewModelScope.launch {
            onResult(memoryRepository.writeFile(memoryId, path, content))
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val path = normalizedPath ?: return
        viewModelScope.launch {
            memoryRepository.deleteFile(memoryId, path)
            onDeleted()
        }
    }
}
