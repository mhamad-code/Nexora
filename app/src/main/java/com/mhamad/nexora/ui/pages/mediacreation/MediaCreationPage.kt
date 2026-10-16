package com.mhamad.nexora.ui.pages.mediacreation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import me.rerere.ui.toast.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ImageToVideo
import me.rerere.hugeicons.stroke.Settings03
import com.mhamad.nexora.R
import com.mhamad.nexora.Screen
import com.mhamad.nexora.data.model.MediaCreationNode
import com.mhamad.nexora.ui.components.nav.BackButton
import com.mhamad.nexora.ui.context.LocalNavController
import com.mhamad.nexora.ui.context.LocalToaster
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 */
@Composable
fun MediaCreationPage(id: String) {
    val vm: MediaCreationVM = koinViewModel(parameters = { parametersOf(id) })
    val navController = LocalNavController.current
    val toaster = LocalToaster.current

    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val uploadConfigured by vm.uploadConfigured.collectAsStateWithLifecycle()
    val recentOutputs by vm.recentOutputs.collectAsStateWithLifecycle()

    var showUploadDialog by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is MediaCreationEvent.Error -> toaster.show(event.message, type = ToastType.Error)
                MediaCreationEvent.UploadNotConfigured -> showUploadDialog = true
                MediaCreationEvent.SessionGone -> navController.popBackStack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = session?.title?.ifBlank { null } ?: stringResource(R.string.media_creation_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    BackButton()
                },
            )
        },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                MediaCreationTimeline(
                    vm = vm,
                    editingNodeId = draft.editingNodeId,
                    hasModels = settings.mediaGenerationProviders.any { it.models.isNotEmpty() },
                    onOpenMediaSettings = { navController.navigate(Screen.SettingMedia) },
                )
            }
            MediaCreationComposer(
                vm = vm,
                draft = draft,
                selection = selection,
                providers = settings.mediaGenerationProviders,
                recentOutputs = recentOutputs,
                uploadConfigured = uploadConfigured,
                onOpenMediaSettings = { navController.navigate(Screen.SettingMedia) },
            )
        }
    }

    RikkaConfirmDialog(
        show = showUploadDialog,
        title = stringResource(R.string.media_creation_page_upload_dialog_title),
        confirmText = stringResource(R.string.media_creation_page_go_configure),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showUploadDialog = false
            navController.navigate(Screen.SettingMedia)
        },
        onDismiss = { showUploadDialog = false },
    ) {
        Text(stringResource(R.string.media_creation_page_upload_dialog_text))
    }
}

@Composable
private fun MediaCreationTimeline(
    vm: MediaCreationVM,
    editingNodeId: Uuid?,
    hasModels: Boolean,
    onOpenMediaSettings: () -> Unit,
) {
    val nodes = vm.nodes.collectAsLazyPagingItems()
    val listState = rememberLazyListState()

    val newestId = nodes.newestId()
    LaunchedEffect(newestId) {
        if (newestId != null && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    LaunchedEffect(vm) {
        vm.submitted.collect { submitted ->
            val key = submitted.nodeId.toString()
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == key }) {
                listState.scrollToItem(submitted.position)
            }
        }
    }

    if (nodes.loadState.refresh is LoadState.NotLoading && nodes.itemCount == 0) {
        TimelineEmpty(hasModels = hasModels, onOpenMediaSettings = onOpenMediaSettings)
        return
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            count = nodes.itemCount,
            key = nodes.itemKey { it.id.toString() },
        ) { index ->
            val node = nodes[index]
            if (node != null) {
                MediaCreationRecordItem(node = node, editing = node.id == editingNodeId, vm = vm)
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {}
            }
        }
    }
}

private fun LazyPagingItems<MediaCreationNode>.newestId(): Uuid? =
    if (itemCount > 0) peek(0)?.id else null

@Composable
private fun TimelineEmpty(
    hasModels: Boolean,
    onOpenMediaSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 12.dp)
                .size(112.dp)
                .background(
                    color = if (hasModels) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    shape = (if (hasModels) MaterialShapes.Flower else MaterialShapes.Cookie4Sided).toShape(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (hasModels) HugeIcons.ImageToVideo else HugeIcons.Settings03,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (hasModels) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (hasModels) {
            Text(
                text = stringResource(R.string.media_creation_page_empty_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = stringResource(R.string.media_creation_page_no_providers),
                style = MaterialTheme.typography.titleMediumEmphasized,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onOpenMediaSettings,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.media_creation_page_go_configure))
            }
        }
    }
}
