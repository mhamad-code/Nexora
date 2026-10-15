package com.mhamad.nexora.di

import com.mhamad.nexora.ui.pages.assistant.AssistantVM
import com.mhamad.nexora.ui.pages.assistant.detail.AssistantDetailVM
import com.mhamad.nexora.ui.pages.backup.BackupVM
import com.mhamad.nexora.ui.pages.chat.ChatDrawerVM
import com.mhamad.nexora.ui.pages.chat.ChatVM
import com.mhamad.nexora.ui.pages.debug.DebugVM
import com.mhamad.nexora.ui.pages.favorite.FavoriteVM
import com.mhamad.nexora.ui.pages.search.SearchVM
import com.mhamad.nexora.ui.pages.history.HistoryVM
import com.mhamad.nexora.ui.pages.stats.StatsVM
import com.mhamad.nexora.ui.pages.imggen.ImgGenVM
import com.mhamad.nexora.ui.pages.mediacreation.MediaCreationSessionsVM
import com.mhamad.nexora.ui.pages.mediacreation.MediaCreationVM
import com.mhamad.nexora.ui.pages.memory.MemoryFileVM
import com.mhamad.nexora.ui.pages.extensions.PromptVM
import com.mhamad.nexora.ui.pages.extensions.QuickMessagesVM
import com.mhamad.nexora.ui.pages.extensions.skills.SkillDetailVM
import com.mhamad.nexora.ui.pages.extensions.skills.SkillsVM
import com.mhamad.nexora.ui.pages.extensions.workspace.WorkspaceDetailVM
import com.mhamad.nexora.ui.pages.extensions.workspace.WorkspaceVM
import com.mhamad.nexora.ui.pages.setting.SettingVM
import com.mhamad.nexora.ui.pages.share.handler.ShareHandlerVM
import com.mhamad.nexora.ui.pages.translator.TranslatorVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            updateChecker = get(),
            analytics = get(),
            filesManager = get(),
            favoriteRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::DebugVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            memoryConsolidationScheduler = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
        )
    }
    viewModel<MemoryFileVM> {
        MemoryFileVM(
            memoryId = it[0],
            path = it[1],
            memoryRepository = get(),
        )
    }
    viewModelOf(::TranslatorVM)
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::MediaCreationSessionsVM)
    viewModel<MediaCreationVM> {
        MediaCreationVM(
            id = it.get(),
            context = get(),
            settingsStore = get(),
            repository = get(),
            service = get(),
            remoteFileStore = get(),
            appScope = get(),
        )
    }
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModelOf(::SkillsVM)
    viewModelOf(::SkillDetailVM)
    viewModelOf(::WorkspaceVM)
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
            terminalSessionManager = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
}
