package com.mhamad.nexora.ui.pages.memory

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mhamad.nexora.R
import com.mhamad.nexora.data.ai.MemoryConsolidationRun
import com.mhamad.nexora.data.model.memoryTitleOf

@Composable
internal fun MemoryConsolidationRun.resultText(): String = when {
    error != null -> stringResource(R.string.assistant_page_memory_dream_failed, error)
    !hasChanges -> stringResource(R.string.assistant_page_memory_dream_no_changes)
    else -> stringResource(
        R.string.assistant_page_memory_dream_updated,
        (changedPaths.map { titles[it] ?: memoryFileTitle(it) } +
            deletedPaths.map { stringResource(R.string.assistant_page_memory_dream_removed, memoryFileTitle(it)) })
            .joinToString(", "),
    )
}

private fun memoryFileTitle(path: String): String = memoryTitleOf(path.substringAfterLast('/').removeSuffix(".md"))
