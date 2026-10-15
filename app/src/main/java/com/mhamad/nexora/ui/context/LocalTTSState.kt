package com.mhamad.nexora.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.mhamad.nexora.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
