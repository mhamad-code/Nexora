package com.mhamad.nexora.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.mhamad.nexora.data.datastore.Settings

val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
