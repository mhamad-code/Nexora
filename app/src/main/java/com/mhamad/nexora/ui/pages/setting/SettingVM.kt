package com.mhamad.nexora.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.mhamad.nexora.data.datastore.Settings
import com.mhamad.nexora.data.datastore.SettingsStore
import com.mhamad.nexora.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = settingsStore.settingsFlow.value.takeUnless { it.init }
                ?: Settings(init = true, providers = emptyList()),
        )

    val launchCount: StateFlow<Int> = settingsStore.launchCountFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }
}
