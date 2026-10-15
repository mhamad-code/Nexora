package com.mhamad.nexora.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.mhamad.nexora.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

