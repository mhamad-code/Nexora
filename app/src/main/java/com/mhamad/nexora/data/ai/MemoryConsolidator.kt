package com.mhamad.nexora.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import com.mhamad.nexora.data.ai.prompts.MemoryConsolidationConversation
import com.mhamad.nexora.data.ai.prompts.buildMemoryConsolidationInput
import com.mhamad.nexora.data.ai.prompts.buildMemoryConsolidationPrompt
import com.mhamad.nexora.data.ai.tools.MemoryToolNames
import com.mhamad.nexora.data.ai.tools.buildMemoryTools
import com.mhamad.nexora.data.ai.tools.createMemoryConversationTools
import com.mhamad.nexora.data.ai.tools.dialogueMessages
import com.mhamad.nexora.data.ai.tools.memoryWritePath
import com.mhamad.nexora.data.datastore.Settings
import com.mhamad.nexora.data.datastore.SettingsStore
import com.mhamad.nexora.data.datastore.findModelById
import com.mhamad.nexora.data.datastore.getAssistantById
import com.mhamad.nexora.data.db.dao.MemoryConsolidationDAO
import com.mhamad.nexora.data.db.entity.MemoryConsolidationEntity
import com.mhamad.nexora.data.model.Assistant
import com.mhamad.nexora.data.model.Conversation
import com.mhamad.nexora.data.model.MemoryFile
import com.mhamad.nexora.data.model.getChatModelOf
import com.mhamad.nexora.data.repository.ConversationRepository
import com.mhamad.nexora.data.repository.MemoryRepository
import com.mhamad.nexora.data.repository.memoryId
import me.rerere.common.android.Logging
import kotlin.uuid.Uuid

private const val TAG = "MemoryConsolidator"

private const val MAX_STEPS = 32
private const val MAX_CONVERSATIONS_PER_RUN = 8

private const val MAX_SCANNED_CONVERSATIONS = 50
private const val MAX_TURNS_PER_CONVERSATION = 12
private const val MAX_TRANSCRIPT_LENGTH = 60_000
private const val RECENT_CONTEXT_MESSAGES = 6
private const val RECENT_CONTEXT_MESSAGE_LENGTH = 500
private const val USER_MESSAGE_LENGTH = 8000
private const val ASSISTANT_MESSAGE_LENGTH = 2000

private const val PENDING_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/**
 *
 */
class MemoryConsolidator(
    private val generationLoop: GenerationLoop,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val consolidationDao: MemoryConsolidationDAO,
    private val settingsStore: SettingsStore,
) : MemoryConsolidationWorker {

    override suspend fun pendingOf(conversation: Conversation): PendingMemoryConversation? =
        withContext(Dispatchers.IO) {
            val memoryId = settingsStore.awaitLoaded().memoryIdOf(conversation) ?: return@withContext null
            val pending = loadPendingTurns(conversation)
            if (pending.turns.isEmpty()) {
                null
            } else {
                PendingMemoryConversation(conversation.id, memoryId, pending.totalTurns)
            }
        }

    override suspend fun memoryIdsWithUpdates(): List<String> = withContext(Dispatchers.IO) {
        val assistants = settingsStore.awaitLoaded().assistants.filter { it.enableMemory }
        if (assistants.isEmpty()) return@withContext emptyList()
        val updated = consolidationDao.getAssistantIdsWithUpdatedConversations(
            assistantIds = assistants.map { it.id.toString() },
            since = System.currentTimeMillis() - PENDING_WINDOW_MS,
        ).toSet()
        assistants.filter { it.id.toString() in updated }.map { it.memoryId }.distinct()
    }

    override suspend fun findPending(memoryId: String): List<PendingMemoryConversation> =
        withContext(Dispatchers.IO) {
            val assistantIds = settingsStore.awaitLoaded().assistants
                .filter { it.enableMemory && it.memoryId == memoryId }
                .map { it.id.toString() }
            if (assistantIds.isEmpty()) return@withContext emptyList()
            val candidates = consolidationDao.getUpdatedConversationIds(
                assistantIds = assistantIds,
                since = System.currentTimeMillis() - PENDING_WINDOW_MS,
                limit = MAX_SCANNED_CONVERSATIONS,
            )
            collectPending(candidates, limit = MAX_CONVERSATIONS_PER_RUN) { id ->
                conversationRepo.getConversationById(Uuid.parse(id))?.let { pendingOf(it) }
            }
        }

    override suspend fun markConsolidated(conversation: Conversation) = withContext(Dispatchers.IO) {
        advance(conversation.id, maxOf(System.currentTimeMillis(), conversation.updateAt.toEpochMilli()))
    }

    override suspend fun consolidate(
        memoryId: String,
        conversationIds: Set<Uuid>,
        fullReview: Boolean,
    ): MemoryConsolidationRun? = withContext(Dispatchers.IO) {
        try {
            consolidateStore(memoryId, conversationIds, fullReview)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logging.log(TAG, "consolidate $memoryId: $e")
            throw e
        }
    }

    private suspend fun consolidateStore(
        memoryId: String,
        conversationIds: Set<Uuid>,
        fullReview: Boolean,
    ): MemoryConsolidationRun? {
        val settings = settingsStore.awaitLoaded()
        val assistants = settings.assistants.filter { it.enableMemory && it.memoryId == memoryId }
        if (assistants.isEmpty()) return null
        val assistantIds = assistants.map { it.id }.toSet()

        val batches = conversationIds
            .mapNotNull { conversationRepo.getConversationById(it) }
            .filter { it.assistantId in assistantIds }
            .sortedByDescending { it.updateAt }
            .mapNotNull { conversation ->
                val pending = loadPendingTurns(conversation)
                if (pending.turns.isEmpty()) null else Batch(conversation, pending)
            }
            .withinTranscriptBudget()
        val files = memoryRepository.getFiles(memoryId)
        if (batches.isEmpty() && (!fullReview || files.isEmpty())) return null

        val model = settings.consolidationModel(batches.firstOrNull()?.conversation, assistants.first())
            ?: error("No model available")
        val tools = buildMemoryTools(memoryRepository, memoryId)
            .filter { fullReview || it.name != MemoryToolNames.DELETE } +
            createMemoryConversationTools(conversationRepo, assistantIds)

        var messages = emptyList<UIMessage>()
        generationLoop.generateText(
            settings = settings,
            model = model,
            messages = listOf(
                UIMessage.user(
                    buildMemoryConsolidationInput(
                        files = files,
                        conversations = batches.map { it.input },
                    )
                )
            ),
            assistant = Assistant(
                systemPrompt = buildMemoryConsolidationPrompt(fullReview),
                streamOutput = false,
                reasoningLevel = settings.fastModelReasoningLevel,
            ),
            tools = tools,
            maxSteps = MAX_STEPS,
            conversationId = Uuid.random(),
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> messages = chunk.messages
            }
        }

        batches.forEach { batch ->
            batch.pending.consolidatedThrough?.let { advance(batch.conversation.id, it) }
        }

        val steps = messages.flatMap { it.getTools() }
        val touched = steps.mapNotNull { it.memoryWritePath() }
            .map { MemoryFile.normalizePath(it) ?: it }
            .distinct()
        val remaining = memoryRepository.getFiles(memoryId).associateBy { it.path }
        return MemoryConsolidationRun(
            memoryId = memoryId,
            finishedAt = System.currentTimeMillis(),
            fullReview = fullReview,
            changedPaths = touched.filter { it in remaining },
            deletedPaths = touched.filterNot { it in remaining },
            titles = touched.mapNotNull { path -> remaining[path]?.let { path to it.title } }.toMap(),
        )
    }

    private suspend fun loadPendingTurns(conversation: Conversation): PendingMemoryTurns {
        val messages = conversation.dialogueMessages()
        val consolidatedAt = consolidationDao.getConsolidatedAt(conversation.id.toString())
            ?: initialMemoryProgress(messages).also { advance(conversation.id, it) }
        val pending = pendingMemoryTurns(
            messages = messages,
            updatedAt = conversation.updateAt.toEpochMilli(),
            consolidatedAt = consolidatedAt,
        )
        if (pending.turns.isEmpty()) pending.consolidatedThrough?.let { advance(conversation.id, it) }
        return pending
    }

    private suspend fun advance(conversationId: Uuid, consolidatedAt: Long) {
        runCatching {
            consolidationDao.upsert(MemoryConsolidationEntity(conversationId.toString(), consolidatedAt))
        }.onFailure {
            Logging.log(TAG, "advance $conversationId: $it")
        }
    }
}

private class Batch(val conversation: Conversation, val pending: PendingMemoryTurns) {
    val input: MemoryConsolidationConversation by lazy {
        val messages = conversation.dialogueMessages().withIndex().filterNot { it.value.isContextCheckpoint }
        val firstPosition = pending.turns.first().position
        MemoryConsolidationConversation(
            id = conversation.id.toString(),
            title = conversation.title,
            recentContext = messages
                .filter { it.index < firstPosition }
                .takeLast(RECENT_CONTEXT_MESSAGES)
                .toMemoryTranscript(RECENT_CONTEXT_MESSAGE_LENGTH, RECENT_CONTEXT_MESSAGE_LENGTH),
            newTurns = pending.turns
                .flatMap { it.messages }
                .toMemoryTranscript(USER_MESSAGE_LENGTH, ASSISTANT_MESSAGE_LENGTH),
        )
    }
}

private fun List<Batch>.withinTranscriptBudget(): List<Batch> {
    var length = 0
    return take(MAX_CONVERSATIONS_PER_RUN).filterIndexed { index, batch ->
        length += batch.input.newTurns.length
        index == 0 || length <= MAX_TRANSCRIPT_LENGTH
    }
}

private fun Settings.memoryIdOf(conversation: Conversation): String? =
    getAssistantById(conversation.assistantId)?.takeIf { it.enableMemory }?.memoryId

private fun Settings.consolidationModel(conversation: Conversation?, assistant: Assistant): Model? {
    val fastModel = findModelById(fastModelId)?.takeIf { ModelAbility.TOOL in it.abilities }
    val chatModel = conversation?.let { getChatModelOf(it) } ?: findModelById(assistant.chatModelId ?: chatModelId)
    return (fastModel ?: chatModel)?.copy(tools = emptySet())
}

/**
 *
 */
internal suspend fun <T> collectPending(
    candidates: List<T>,
    limit: Int,
    check: suspend (T) -> PendingMemoryConversation?,
): List<PendingMemoryConversation> {
    val found = mutableListOf<PendingMemoryConversation>()
    for (candidate in candidates) {
        if (found.size >= limit) break
        check(candidate)?.let(found::add)
    }
    return found
}

internal class MemoryTurn(val messages: List<IndexedValue<UIMessage>>) {
    val position: Int get() = messages.first().index
    val userMessage: UIMessage get() = messages.first().value

    val isFinished: Boolean
        get() = messages.last().value.let { last ->
            last.role == MessageRole.ASSISTANT && last.getTools().all { it.isExecuted }
        }
}

internal class PendingMemoryTurns(
    val turns: List<MemoryTurn>,
    val totalTurns: Int,
    val consolidatedThrough: Long?,
)

private fun memoryTurnsOf(messages: List<UIMessage>): List<MemoryTurn> {
    val indexed = messages.withIndex().filterNot { it.value.isContextCheckpoint }
    val starts = indexed.indices.filter { indexed[it].value.role == MessageRole.USER }
    return starts.mapIndexed { i, start ->
        MemoryTurn(indexed.subList(start, starts.getOrElse(i + 1) { indexed.size }))
    }
}

private val MemoryTurn.hasUserText: Boolean get() = userMessage.toText().isNotBlank()

/**
 *
 */
internal fun initialMemoryProgress(
    messages: List<UIMessage>,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Long {
    val first = memoryTurnsOf(messages).filter { it.hasUserText }.takeLast(MAX_TURNS_PER_CONVERSATION).firstOrNull()
        ?: return 0
    return first.userMessage.createdAt.toInstant(timeZone).toEpochMilliseconds() - 1
}

/**
 *
 */
internal fun pendingMemoryTurns(
    messages: List<UIMessage>,
    updatedAt: Long,
    consolidatedAt: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): PendingMemoryTurns {
    fun UIMessage.time() = createdAt.toInstant(timeZone).toEpochMilliseconds()

    val turns = memoryTurnsOf(messages)
    var pending = turns.filter { it.userMessage.time() > consolidatedAt }
    var consolidatedThrough = maxOf(updatedAt, turns.lastOrNull()?.messages?.last()?.value?.time() ?: 0)
    val last = turns.lastOrNull()
    if (last != null && !last.isFinished) {
        pending = pending.filter { it !== last }
        consolidatedThrough = last.userMessage.time() - 1
    }

    pending = pending.filter { it.hasUserText }
    val batch = pending.take(MAX_TURNS_PER_CONVERSATION)
    if (batch.isNotEmpty()) {
        val batchEnd = batch.last().userMessage.time()
        pending.drop(batch.size).firstOrNull { it.userMessage.time() > batchEnd }?.let { next ->
            consolidatedThrough = next.userMessage.time() - 1
        }
        consolidatedThrough = maxOf(consolidatedThrough, batchEnd)
    }

    return PendingMemoryTurns(
        turns = batch,
        totalTurns = pending.size,
        consolidatedThrough = consolidatedThrough.takeIf { it > consolidatedAt },
    )
}

/**
 *
 */
internal fun List<IndexedValue<UIMessage>>.toMemoryTranscript(userLength: Int, assistantLength: Int): String =
    flatMap { (position, message) ->
        val maxLength = if (message.role == MessageRole.USER) userLength else assistantLength
        val text = message.toText().trim().let { if (it.length > maxLength) it.take(maxLength) + "..." else it }
        val memoryWrites = message.getTools().mapNotNull { tool ->
            tool.memoryWritePath()?.let { "[assistant updated memory: ${tool.toolName} $it]" }
        }
        listOfNotNull("[${message.role.name} #$position]: $text".takeIf { text.isNotEmpty() }) + memoryWrites
    }.joinToString("\n\n")
