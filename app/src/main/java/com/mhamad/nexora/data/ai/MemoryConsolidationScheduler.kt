package com.mhamad.nexora.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.mhamad.nexora.data.model.Conversation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

data class PendingMemoryConversation(
    val conversationId: Uuid,
    val memoryId: String,
    val turns: Int,
)

data class MemoryConsolidationRun(
    val memoryId: String,
    val finishedAt: Long,
    val fullReview: Boolean,
    val changedPaths: List<String> = emptyList(),
    val deletedPaths: List<String> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
    val duration: Duration = Duration.ZERO,
    val error: String? = null,
) {
    val hasChanges: Boolean get() = changedPaths.isNotEmpty() || deletedPaths.isNotEmpty()
}

data class MemoryConsolidationStatus(
    val pendingTurns: Int = 0,
    val running: Boolean = false,
    val lastRun: MemoryConsolidationRun? = null,
)

interface MemoryConsolidationWorker {
    suspend fun pendingOf(conversation: Conversation): PendingMemoryConversation?

    suspend fun memoryIdsWithUpdates(): List<String>

    suspend fun findPending(memoryId: String): List<PendingMemoryConversation>

    suspend fun markConsolidated(conversation: Conversation)

    /**
     */
    suspend fun consolidate(
        memoryId: String,
        conversationIds: Set<Uuid>,
        fullReview: Boolean,
    ): MemoryConsolidationRun?
}

fun interface MemoryDreamKeepAlive {
    fun acquire(): AutoCloseable
}

/**
 *
 */
class MemoryConsolidationScheduler(
    private val scope: CoroutineScope,
    private val worker: MemoryConsolidationWorker,
    private val keepAlive: MemoryDreamKeepAlive = MemoryDreamKeepAlive { AutoCloseable {} },
    private val isAppVisible: () -> Boolean = { true },
    private val idleDelay: Duration = 2.minutes,
    private val maxPendingTurns: Int = 6,
) {
    private val lock = Any()

    private val pending = mutableMapOf<String, Map<Uuid, Int>>()
    private val timers = mutableMapOf<String, Job>()
    private val runLocks = mutableMapOf<String, Mutex>()

    private val failed = mutableSetOf<String>()

    private val status = MutableStateFlow<Map<String, MemoryConsolidationStatus>>(emptyMap())

    fun statusOf(memoryId: String): Flow<MemoryConsolidationStatus> =
        status.map { it[memoryId] ?: MemoryConsolidationStatus() }.distinctUntilChanged()

    fun onTurnFinished(conversation: Conversation) {
        val hold = if (isAppVisible()) null else keepAlive.acquire()
        scope.launch {
            var handedOver = false
            try {
                val item = worker.pendingOf(conversation) ?: return@launch
                markPending(item)
                if (item.turns >= maxPendingTurns) {
                    runAutomatically(item.memoryId)
                } else {
                    restartTimer(item.memoryId, hold)
                    handedOver = true
                }
            } finally {
                if (!handedOver) hold?.close()
            }
        }
    }

    suspend fun onConversationForked(fork: Conversation) {
        worker.markConsolidated(fork)
    }

    fun onAppHidden() {
        synchronized(lock) { pending.filterValues { it.isNotEmpty() }.keys.toList() }.forEach(::launchRun)
    }

    fun onConversationLeft(conversationId: Uuid) {
        val memoryId = synchronized(lock) {
            pending.entries.firstOrNull { conversationId in it.value }?.key
        } ?: return
        launchRun(memoryId)
    }

    suspend fun resumePending() {
        worker.memoryIdsWithUpdates().forEach { memoryId ->
            if (mergePending(memoryId, worker.findPending(memoryId))) restartTimer(memoryId)
        }
    }

    fun organize(memoryId: String) {
        if (status.value[memoryId]?.running == true) return
        updateStatus(memoryId) { it.copy(running = true) }
        cancelTimer(memoryId)
        scope.launch {
            var started = false
            try {
                mergePending(memoryId, worker.findPending(memoryId))
                started = true
                run(memoryId, fullReview = true)
            } finally {
                if (!started) updateStatus(memoryId) { it.copy(running = false) }
            }
        }
    }

    private fun launchRun(memoryId: String) {
        scope.launch { runAutomatically(memoryId) }
    }

    private suspend fun runAutomatically(memoryId: String) {
        cancelTimer(memoryId)
        run(memoryId, fullReview = false)
    }

    private suspend fun run(
        memoryId: String,
        fullReview: Boolean,
    ): MemoryConsolidationRun? = synchronized(lock) { runLocks.getOrPut(memoryId) { Mutex() } }.withLock {
        val ids = synchronized(lock) {
            if (!fullReview && memoryId in failed) return null
            pending[memoryId]?.keys.orEmpty()
        }
        if (ids.isEmpty() && !fullReview) return null

        updateStatus(memoryId) { it.copy(running = true) }
        var run: MemoryConsolidationRun? = null
        val hold = keepAlive.acquire()
        val started = TimeSource.Monotonic.markNow()
        try {
            val result = try {
                worker.consolidate(memoryId, ids, fullReview)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MemoryConsolidationRun(
                    memoryId = memoryId,
                    finishedAt = System.currentTimeMillis(),
                    fullReview = fullReview,
                    error = e.message ?: e.toString(),
                )
            }
            run = result?.copy(duration = started.elapsedNow())
            if (run?.error != null) {
                synchronized(lock) { failed += memoryId }
            } else {
                synchronized(lock) {
                    failed -= memoryId
                    pending[memoryId] = pending[memoryId].orEmpty() - ids
                }
                if (mergePending(memoryId, worker.findPending(memoryId))) restartTimer(memoryId)
            }
        } finally {
            hold.close()
            updateStatus(memoryId) { it.copy(running = false, lastRun = run ?: it.lastRun) }
        }
        run
    }

    private fun restartTimer(memoryId: String, hold: AutoCloseable? = null) {
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            delay(idleDelay)
            val self = currentCoroutineContext().job
            synchronized(lock) { if (timers[memoryId] === self) timers.remove(memoryId) }
            runAutomatically(memoryId)
        }
        if (hold != null) timer.invokeOnCompletion { hold.close() }
        synchronized(lock) { timers.put(memoryId, timer) }?.cancel()
        timer.start()
    }

    private fun cancelTimer(memoryId: String) {
        synchronized(lock) { timers.remove(memoryId) }?.cancel()
    }

    private fun markPending(item: PendingMemoryConversation) {
        synchronized(lock) { failed -= item.memoryId }
        mergePending(item.memoryId, listOf(item))
    }

    /**
     *
     */
    private fun mergePending(memoryId: String, items: List<PendingMemoryConversation>): Boolean {
        val conversations = synchronized(lock) {
            (pending[memoryId].orEmpty() + items.associate { it.conversationId to it.turns })
                .also { pending[memoryId] = it }
        }
        updateStatus(memoryId) { it.copy(pendingTurns = conversations.values.sum()) }
        return conversations.isNotEmpty()
    }

    private fun updateStatus(memoryId: String, transform: (MemoryConsolidationStatus) -> MemoryConsolidationStatus) {
        status.update { it + (memoryId to transform(it[memoryId] ?: MemoryConsolidationStatus())) }
    }
}
