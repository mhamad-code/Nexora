package com.mhamad.nexora.ui.pages.mediacreation

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationCapabilities
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import com.mhamad.nexora.AppScope
import com.mhamad.nexora.R
import com.mhamad.nexora.data.datastore.SettingsStore
import com.mhamad.nexora.data.files.MediaCreationFiles
import com.mhamad.nexora.data.remote.RemoteFileStore
import com.mhamad.nexora.data.model.MediaCreationAsset
import com.mhamad.nexora.data.model.MediaCreationAssetType
import com.mhamad.nexora.data.model.MediaCreationDraft
import com.mhamad.nexora.data.model.MediaCreationNode
import com.mhamad.nexora.data.model.MediaCreationOutput
import com.mhamad.nexora.data.model.MediaCreationParams
import com.mhamad.nexora.data.model.MediaCreationRecord
import com.mhamad.nexora.data.model.MediaCreationSession
import com.mhamad.nexora.data.model.canSubmit
import com.mhamad.nexora.data.model.supportedBy
import com.mhamad.nexora.data.model.withRequired
import com.mhamad.nexora.data.repository.MediaCreationRepository
import com.mhamad.nexora.service.MediaCreationService
import com.mhamad.nexora.service.MediaCreationSubmission
import me.rerere.ui.sketch.SketchResult
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val TAG = "MediaCreationVM"

data class MediaCreationModelSelection(
    val provider: MediaGenerationProviderSetting,
    val model: MediaGenerationModel,
    val capabilities: MediaGenerationCapabilities,
)

data class MediaCreationRecentOutput(
    val recordId: Uuid,
    val output: MediaCreationOutput,
)

data class MediaCreationSubmitted(
    val nodeId: Uuid,
    val position: Int,
)

sealed interface MediaCreationEvent {
    data class Error(val message: String) : MediaCreationEvent

    data object UploadNotConfigured : MediaCreationEvent

    data object SessionGone : MediaCreationEvent
}

@OptIn(FlowPreview::class)
class MediaCreationVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val repository: MediaCreationRepository,
    private val service: MediaCreationService,
    private val remoteFileStore: RemoteFileStore,
    private val appScope: AppScope,
) : ViewModel() {
    val sessionId: Uuid = Uuid.parse(id)

    private val draftState = MutableStateFlow<MediaCreationDraft?>(null)

    private var savedDraft: MediaCreationDraft? = null

    val promptState = TextFieldState()

    val settings = settingsStore.settingsFlow

    val draft: StateFlow<MediaCreationDraft> = draftState
        .map { it ?: MediaCreationDraft() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MediaCreationDraft())

    val session: StateFlow<MediaCreationSession?> = repository.observeSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val nodes: Flow<PagingData<MediaCreationNode>> = repository.observeNodes(sessionId).cachedIn(viewModelScope)

    val selection: StateFlow<MediaCreationModelSelection?> =
        combine(settingsStore.settingsFlow, draft) { settings, draft ->
            settings.mediaGenerationProviders.findSelection(draft.modelId)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uploadConfigured: StateFlow<Boolean> = settingsStore.settingsFlow
        .map { remoteFileStore.isConfigured }
        .stateIn(viewModelScope, SharingStarted.Eagerly, remoteFileStore.isConfigured)

    val recentOutputs: StateFlow<List<MediaCreationRecentOutput>> =
        repository.observeRecentSucceededRecords(RECENT_RECORD_LIMIT)
            .map { records ->
                records.flatMap { record -> record.outputs.map { MediaCreationRecentOutput(record.id, it) } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = Channel<MediaCreationEvent>(Channel.BUFFERED)
    val events: Flow<MediaCreationEvent> = _events.receiveAsFlow()

    private val _submitted = MutableSharedFlow<MediaCreationSubmitted>(extraBufferCapacity = 1)

    val submitted: Flow<MediaCreationSubmitted> = _submitted.asSharedFlow()

    private var generateJob: Job? = null

    private val draftCopyMutex = Mutex()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            repository.observeSession(sessionId).first { it == null }
            _events.send(MediaCreationEvent.SessionGone)
        }
        viewModelScope.launch {
            snapshotFlow { promptState.text.toString() }.collect { syncPrompt() }
        }
        viewModelScope.launch {
            draftState.filterNotNull().debounce(DRAFT_SAVE_DELAY).collect { save(it) }
        }
    }

    override fun onCleared() {
        syncPrompt()
        val draft = draftState.value
        if (draft != null && draft != savedDraft) {
            appScope.launch { repository.saveDraft(sessionId, draft) }
        }
    }

    fun resolve(path: String): File = repository.resolve(path)

    private suspend fun load() {
        val session = repository.getSession(sessionId) ?: return
        val providers = settingsStore.awaitLoaded().mediaGenerationProviders
        val assets = withContext(Dispatchers.IO) {
            val assets = session.draft.assets.filter { repository.resolve(it.path).isFile }
            val referenced = assets.map { repository.resolve(it.path) }.toSet()
            repository.draftDir(session.id).listFiles()?.filterNot { it in referenced }?.forEach { it.delete() }
            assets
        }
        val modelId = session.draft.modelId?.takeIf { providers.findSelection(it) != null }
            ?: providers.firstNotNullOfOrNull { provider ->
                provider.models.firstOrNull { provider.capabilities(it.kind) != null }
            }?.id
        val editingNodeId = session.draft.editingNodeId?.takeIf { repository.hasNode(it) }
        savedDraft = session.draft
        draftState.value = session.draft.copy(modelId = modelId, assets = assets, editingNodeId = editingNodeId)
        promptState.setTextAndPlaceCursorAtEnd(session.draft.prompt)
    }

    private suspend fun save(draft: MediaCreationDraft) {
        if (draft == savedDraft) return
        repository.saveDraft(sessionId, draft)
        savedDraft = draft
    }


    private fun syncPrompt() = updateDraft { it.copy(prompt = promptState.text.toString()) }

    fun updateParams(params: MediaCreationParams) = updateDraft { it.copy(params = params) }

    fun clearDraft() {
        promptState.clearText()
        updateDraft { it.copy(prompt = "", assets = emptyList(), editingNodeId = null) }
    }

    fun stopEditing() = updateDraft { it.copy(editingNodeId = null) }

    /**
     */
    fun selectModel(provider: MediaGenerationProviderSetting, model: MediaGenerationModel) = updateDraft { draft ->
        val capabilities = provider.capabilities(model.kind) ?: return@updateDraft draft
        val previous = currentSelection(draft)
        val sameShape = previous != null &&
            previous.provider::class == provider::class &&
            previous.model.kind == model.kind
        draft.copy(
            modelId = model.id,
            params = if (sameShape) draft.params else MediaCreationParams(),
            assets = draft.assets.supportedBy(capabilities),
        )
    }

    fun importAssets(uris: List<Uri>, type: MediaCreationAssetType, role: ImageRole) {
        if (draftState.value == null || uris.isEmpty()) return
        viewModelScope.launch {
            val dir = repository.draftDir(sessionId)
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        when (type) {
                            MediaCreationAssetType.IMAGE -> MediaCreationFiles.importImage(context, uri, dir)
                            MediaCreationAssetType.VIDEO -> MediaCreationFiles.importVideo(context, uri, dir)
                        }
                    }.onFailure {
                        Log.e(TAG, "Failed to import $uri", it)
                    }.getOrNull()
                }
            }
            if (files.size < uris.size) {
                _events.send(
                    MediaCreationEvent.Error(
                        context.getString(R.string.media_creation_page_files_unreadable, uris.size - files.size)
                    )
                )
            }
            files.forEach { addAsset(MediaCreationAsset(repository.relativePath(it), type, role)) }
        }
    }

    /**
     */
    fun addSketch(result: SketchResult, role: ImageRole, replacing: MediaCreationAsset? = null) {
        if (draftState.value == null) return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = repository.draftDir(sessionId).apply { mkdirs() }
                    File(dir, "${Uuid.random()}.${result.extension}").also { it.writeBytes(result.encode()) }
                }.onFailure {
                    Log.e(TAG, "Failed to save sketch", it)
                }.getOrNull()
            }
            if (file == null) {
                _events.send(MediaCreationEvent.Error(context.getString(R.string.media_creation_page_asset_add_failed)))
                return@launch
            }
            val asset = MediaCreationAsset(repository.relativePath(file), MediaCreationAssetType.IMAGE, role)
            updateDraft { draft ->
                val assets = if (replacing != null && replacing in draft.assets) {
                    draft.assets.map { if (it == replacing) asset else it }
                } else {
                    draft.assets + asset
                }
                draft.withAssets(assets)
            }
        }
    }

    fun removeAsset(asset: MediaCreationAsset) = updateDraft { it.copy(assets = it.assets - asset) }

    fun setAssetRole(asset: MediaCreationAsset, role: ImageRole) = updateDraft { draft ->
        draft.withAssets(draft.assets - asset + asset.copy(role = role))
    }

    /**
     */
    fun useImage(output: MediaCreationOutput, role: ImageRole) {
        viewModelScope.launch {
            if (!ensureModel { role in it.imageRoles }) return@launch
            addCopy(output.path, MediaCreationAssetType.IMAGE, role)
        }
    }

    fun useVideo(output: MediaCreationOutput) {
        viewModelScope.launch {
            if (!ensureModel { it.videoInput }) return@launch
            addCopy(output.path, MediaCreationAssetType.VIDEO)
        }
    }

    fun continueVideo(output: MediaCreationOutput) {
        viewModelScope.launch {
            val framePath = output.lastFramePath?.let { copyToDraft(it) } ?: withContext(Dispatchers.IO) {
                val target = File(repository.draftDir(sessionId), "${Uuid.random()}.jpg")
                val extracted = MediaCreationFiles.extractFrame(repository.resolve(output.path), target, last = true)
                if (extracted) repository.relativePath(target) else null
            }
            if (framePath == null) {
                _events.send(
                    MediaCreationEvent.Error(context.getString(R.string.media_creation_page_last_frame_failed))
                )
                return@launch
            }
            if (!ensureModel { ImageRole.FIRST_FRAME in it.imageRoles }) return@launch
            addAsset(MediaCreationAsset(framePath, MediaCreationAssetType.IMAGE, ImageRole.FIRST_FRAME))
        }
    }

    private suspend fun addCopy(path: String, type: MediaCreationAssetType, role: ImageRole = ImageRole.REFERENCE) {
        val copy = copyToDraft(path)
        if (copy == null) {
            _events.send(MediaCreationEvent.Error(context.getString(R.string.media_creation_page_asset_add_failed)))
            return
        }
        addAsset(MediaCreationAsset(copy, type, role))
    }

    /**
     */
    private suspend fun copyToDraft(path: String): String? = draftCopyMutex.withLock {
        withContext(Dispatchers.IO) {
            val source = repository.resolve(path)
            val target = File(repository.draftDir(sessionId), "${source.parentFile?.name}_${source.name}")
            runCatching {
                if (!target.isFile) source.copyTo(target)
                repository.relativePath(target)
            }.onFailure {
                Log.e(TAG, "Failed to copy $path into the draft", it)
                target.delete()
            }.getOrNull()
        }
    }

    private fun addAsset(asset: MediaCreationAsset) = updateDraft { it.withAssets(it.assets + asset) }

    private fun MediaCreationDraft.withAssets(assets: List<MediaCreationAsset>): MediaCreationDraft {
        val capabilities = currentSelection(this)?.capabilities
        val supported = if (capabilities != null) assets.distinct().supportedBy(capabilities) else assets.distinct()
        return copy(assets = supported.takeLast(MAX_ASSETS))
    }

    private fun updateDraft(transform: (MediaCreationDraft) -> MediaCreationDraft) {
        val draft = draftState.value ?: return
        draftState.value = transform(draft)
    }

    private fun currentSelection(draft: MediaCreationDraft? = draftState.value): MediaCreationModelSelection? =
        settingsStore.settingsFlow.value.mediaGenerationProviders.findSelection(draft?.modelId)

    private suspend fun ensureModel(requirement: (MediaGenerationCapabilities) -> Boolean): Boolean {
        if (currentSelection()?.capabilities?.let(requirement) == true) return true
        val candidates = settingsStore.settingsFlow.value.mediaGenerationProviders.flatMap { provider ->
            provider.models.mapNotNull { model ->
                provider.capabilities(model.kind)?.takeIf(requirement)
                    ?.let { MediaCreationModelSelection(provider, model, it) }
            }
        }
        if (candidates.isEmpty()) {
            _events.send(MediaCreationEvent.Error(context.getString(R.string.media_creation_page_no_video_model)))
            return false
        }
        val recent = repository.getLatestRecordOfKind(MediaKind.VIDEO)
        val target = candidates.find { it.provider.id == recent?.providerId && it.model.modelId == recent.modelId }
            ?: candidates.first()
        selectModel(target.provider, target.model)
        return true
    }


    fun generate() {
        if (generateJob?.isActive == true) return
        syncPrompt()
        val submitted = draftState.value ?: return
        val selection = currentSelection(submitted)
        if (selection == null) {
            _events.trySend(
                MediaCreationEvent.Error(context.getString(R.string.media_creation_page_select_model_first))
            )
            return
        }
        if (!submitted.canSubmit(selection.capabilities)) return
        generateJob = submit(
            MediaCreationSubmission(
                sessionId = sessionId,
                nodeId = submitted.editingNodeId,
                provider = selection.provider,
                model = selection.model,
                prompt = submitted.prompt,
                params = submitted.params,
                assets = submitted.assets,
            ),
            selection.capabilities,
        ) {
            val untouched = promptState.text.toString() == submitted.prompt &&
                draftState.value?.assets == submitted.assets
            if (untouched) {
                clearDraft()
            } else {
                updateDraft {
                    if (it.editingNodeId == submitted.editingNodeId) it.copy(editingNodeId = null) else it
                }
            }
        }
    }

    fun rerun(record: MediaCreationRecord) {
        val provider = settingsStore.settingsFlow.value.mediaGenerationProviders.find { it.id == record.providerId }
        val capabilities = provider?.capabilities(record.kind)
        if (provider == null || capabilities == null) {
            _events.trySend(
                MediaCreationEvent.Error(
                    context.getString(R.string.media_creation_error_provider_deleted, record.providerName)
                )
            )
            return
        }
        submit(
            MediaCreationSubmission(
                sessionId = record.sessionId,
                nodeId = record.nodeId,
                provider = provider,
                model = MediaGenerationModel(modelId = record.modelId, kind = record.kind),
                prompt = record.prompt,
                params = record.params,
                assets = record.inputs,
            ),
            capabilities,
        )
    }

    private fun submit(
        submission: MediaCreationSubmission,
        capabilities: MediaGenerationCapabilities,
        onSubmitted: () -> Unit = {},
    ): Job? {
        if (capabilities.requiresRemoteInputs && submission.assets.isNotEmpty() && !remoteFileStore.isConfigured) {
            _events.trySend(MediaCreationEvent.UploadNotConfigured)
            return null
        }
        val defaults = submission.provider.presets(submission.model.kind).defaults
        val completed = submission.copy(params = submission.params.withRequired(capabilities, defaults))
        return viewModelScope.launch {
            try {
                val record = service.submit(completed)
                _submitted.tryEmit(MediaCreationSubmitted(record.nodeId, repository.getNodePosition(record.nodeId)))
                onSubmitted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to submit", e)
                _events.send(
                    MediaCreationEvent.Error(e.message ?: context.getString(R.string.media_creation_page_submit_failed))
                )
            }
        }
    }

    fun editFrom(record: MediaCreationRecord) {
        updateDraft { draft ->
            val providers = settingsStore.settingsFlow.value.mediaGenerationProviders
            val model = providers.find { it.id == record.providerId }
                ?.models?.find { it.modelId == record.modelId && it.kind == record.kind }
            draft.copy(
                modelId = model?.id ?: draft.modelId,
                prompt = record.prompt,
                params = if (model != null) record.params else draft.params,
                editingNodeId = record.nodeId,
            ).withAssets(record.inputs)
        }
        promptState.setTextAndPlaceCursorAtEnd(record.prompt)
    }

    fun retry(record: MediaCreationRecord) {
        viewModelScope.launch { service.retry(record.id) }
    }

    fun cancel(record: MediaCreationRecord) {
        viewModelScope.launch { service.cancel(record.id) }
    }

    fun switchVersion(node: MediaCreationNode, offset: Int) {
        viewModelScope.launch { repository.switchVersion(node.id, offset) }
    }

    fun delete(record: MediaCreationRecord) {
        viewModelScope.launch {
            service.deleteRecord(record.id)
            val nodeRemoved = !repository.hasNode(record.nodeId)
            val recordDir = repository.relativePath(repository.recordDir(record.sessionId, record.id)) + File.separator
            updateDraft { draft ->
                draft.copy(
                    assets = draft.assets.filterNot { it.path.startsWith(recordDir) },
                    editingNodeId = draft.editingNodeId.takeUnless { nodeRemoved && it == record.nodeId },
                )
            }
        }
    }

    companion object {
        private const val MAX_ASSETS = 16
        private const val RECENT_RECORD_LIMIT = 60
        private val DRAFT_SAVE_DELAY = 500.milliseconds
    }
}

private fun List<MediaGenerationProviderSetting>.findSelection(modelId: Uuid?): MediaCreationModelSelection? {
    if (modelId == null) return null
    forEach { provider ->
        val model = provider.models.find { it.id == modelId } ?: return@forEach
        val capabilities = provider.capabilities(model.kind) ?: return@forEach
        return MediaCreationModelSelection(provider, model, capabilities)
    }
    return null
}
