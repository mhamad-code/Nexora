package com.mhamad.nexora.ui.pages.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import com.mhamad.nexora.R
import com.mhamad.nexora.data.model.MemoryFile
import com.mhamad.nexora.data.model.memoryTitleOf
import com.mhamad.nexora.data.repository.MemoryWriteResult
import com.mhamad.nexora.ui.components.nav.BackButton
import com.mhamad.nexora.ui.components.richtext.MarkdownBlock
import com.mhamad.nexora.ui.components.ui.CardGroup
import com.mhamad.nexora.ui.context.LocalNavController
import com.mhamad.nexora.ui.context.LocalToaster
import com.mhamad.nexora.ui.theme.CustomColors
import com.mhamad.nexora.ui.theme.JetbrainsMono
import com.mhamad.nexora.utils.toLocalDateTime
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant

/**
 *
 */
@Composable
fun MemoryFilePage(memoryId: String, path: String?) {
    val vm: MemoryFileVM = koinViewModel(
        parameters = {
            parametersOf(memoryId, path)
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val isNew = path == null
    val file = (state as? MemoryFileState.Loaded)?.file
    var editing by rememberSaveable { mutableStateOf(isNew) }
    var draftPath by rememberSaveable { mutableStateOf("") }
    var draftTitle by rememberSaveable { mutableStateOf("") }
    var draftDescription by rememberSaveable { mutableStateOf("") }
    var draftAliases by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var draftAliasInput by rememberSaveable { mutableStateOf("") }
    var draftBody by rememberSaveable { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val pathConflictMessage = stringResource(R.string.memory_file_page_path_conflict)

    val normalizedDraftPath = MemoryFile.normalizePath(draftPath)
    val draftPathValid = normalizedDraftPath != null
    val base = file ?: remember(normalizedDraftPath) {
        MemoryFile(path = normalizedDraftPath.orEmpty(), content = "", updatedAt = 0)
    }
    val draftContent = remember(base, draftTitle, draftDescription, draftAliases, draftAliasInput, draftBody) {
        base.contentWith(draftTitle, draftDescription, draftAliases.withAlias(draftAliasInput), draftBody)
    }
    val unchangedContent = remember(file) {
        file?.run { contentWith(title, description, aliases, editableBody) }
    }
    val draftContentValid = draftContent.toByteArray().size <= MemoryFile.MAX_BYTES
    val canSave = draftContentValid && if (isNew) draftPathValid else draftContent != unchangedContent

    fun onSaved(result: MemoryWriteResult) {
        when (result) {
            is MemoryWriteResult.Success -> if (isNew) navController.popBackStack() else editing = false
            is MemoryWriteResult.Conflict -> toaster.show(
                message = pathConflictMessage,
                type = ToastType.Error,
            )

            is MemoryWriteResult.Rejected -> toaster.show(message = result.reason, type = ToastType.Error)
        }
    }

    BackHandler(enabled = editing && !isNew) { editing = false }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = (file?.title ?: MemoryFile(path = path ?: draftPath, content = "", updatedAt = 0).title)
                            .ifEmpty { stringResource(R.string.memory_file_page_new) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                subtitle = {
                    if (path != null) {
                        Text(text = file?.path ?: path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (editing && !isNew) {
                        FilledTonalIconButton(
                            onClick = { editing = false },
                            shapes = IconButtonDefaults.shapes(),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = CustomColors.listItemColors.containerColor
                            ),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.cancel),
                            )
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (editing) {
                        TextButton(
                            enabled = canSave,
                            onClick = {
                                if (isNew) {
                                    vm.create(draftPath, draftContent, ::onSaved)
                                } else {
                                    vm.save(draftContent, ::onSaved)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.assistant_page_save))
                        }
                    } else if (file != null) {
                        IconButton(
                            onClick = {
                                draftTitle = file.title
                                draftDescription = file.description
                                draftAliases = file.aliases
                                draftAliasInput = ""
                                draftBody = file.editableBody
                                editing = true
                            },
                        ) {
                            Icon(
                                imageVector = HugeIcons.PencilEdit01,
                                contentDescription = stringResource(R.string.edit),
                            )
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.delete),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            editing -> MemoryFileEditor(
                innerPadding = innerPadding,
                path = draftPath.takeIf { isNew },
                onPathChange = { draftPath = it },
                pathValid = draftPathValid,
                title = draftTitle,
                onTitleChange = { draftTitle = it },
                titlePlaceholder = memoryTitleOf(base.name),
                description = draftDescription,
                onDescriptionChange = { draftDescription = it },
                aliases = draftAliases,
                onAliasesChange = { draftAliases = it },
                aliasInput = draftAliasInput,
                onAliasInputChange = { draftAliasInput = it },
                body = draftBody,
                onBodyChange = { draftBody = it },
                contentValid = draftContentValid,
            )

            file != null -> MemoryFileDetail(innerPadding = innerPadding, file = file)

            state is MemoryFileState.Missing -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.memory_file_page_missing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteDialog = false
            vm.delete { navController.popBackStack() }
        },
        onDismiss = { showDeleteDialog = false },
        text = {
            Text(text = file?.path.orEmpty())
        }
    )
}

@Composable
private fun MemoryFileDetail(
    innerPadding: PaddingValues,
    file: MemoryFile,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        CardGroup {
            item(
                overlineContent = { Text(stringResource(R.string.memory_file_page_last_updated)) },
                headlineContent = { Text(Instant.ofEpochMilli(file.updatedAt).toLocalDateTime()) },
            )
            if (file.description.isNotEmpty()) {
                item(
                    overlineContent = { Text(stringResource(R.string.memory_file_page_summary)) },
                    headlineContent = { Text(file.description) },
                )
            }
            if (file.aliases.isNotEmpty()) {
                item(
                    overlineContent = { Text(stringResource(R.string.memory_file_page_aliases)) },
                    headlineContent = { Text(file.aliases.joinToString(", ")) },
                )
            }
            if (file.body.isNotBlank()) {
                formItem {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.memory_file_page_details),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            MarkdownBlock(
                                content = file.displayBody,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 */
@Composable
private fun MemoryFileEditor(
    innerPadding: PaddingValues,
    path: String?,
    onPathChange: (String) -> Unit,
    pathValid: Boolean,
    title: String,
    onTitleChange: (String) -> Unit,
    titlePlaceholder: String,
    description: String,
    onDescriptionChange: (String) -> Unit,
    aliases: List<String>,
    onAliasesChange: (List<String>) -> Unit,
    aliasInput: String,
    onAliasInputChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    contentValid: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.memory_file_page_edit_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardGroup {
            if (path != null) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.memory_file_page_path)) },
                ) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = onPathChange,
                        placeholder = { Text("/topics/food.md") },
                        isError = path.isNotEmpty() && !pathValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_title)) },
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    placeholder = { Text(titlePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_summary)) },
            ) {
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_aliases)) },
            ) {
                val addAlias = {
                    onAliasesChange(aliases.withAlias(aliasInput))
                    onAliasInputChange("")
                }
                if (aliases.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        aliases.forEach { alias ->
                            InputChip(
                                selected = false,
                                onClick = { onAliasesChange(aliases - alias) },
                                label = { Text(text = alias, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = HugeIcons.Cancel01,
                                        contentDescription = stringResource(R.string.delete),
                                        modifier = Modifier.size(InputChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = aliasInput,
                    onValueChange = onAliasInputChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addAlias() }),
                    trailingIcon = {
                        IconButton(onClick = addAlias, enabled = aliasInput.isNotBlank()) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(R.string.add),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_details)) },
            ) {
                OutlinedTextField(
                    value = body,
                    onValueChange = onBodyChange,
                    isError = !contentValid,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun List<String>.withAlias(input: String): List<String> {
    val alias = input.trim()
    return if (alias.isEmpty() || alias in this) this else this + alias
}
