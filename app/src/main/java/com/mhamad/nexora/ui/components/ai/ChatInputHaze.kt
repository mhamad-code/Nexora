package com.mhamad.nexora.ui.components.ai

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.GlassStyleScope
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.material3.Material3
import com.mhamad.nexora.data.datastore.BackgroundEffectType
import com.mhamad.nexora.data.datastore.DisplaySetting
import com.mhamad.nexora.ui.theme.LocalDarkMode

/**
 *
 */
@Composable
internal fun Modifier.chatInputHaze(
    hazeState: HazeState,
    displaySetting: DisplaySetting,
    containerColor: Color,
    shape: RoundedCornerShape,
): Modifier {
    if (!displaySetting.enableBlurEffect) return this
    return when (displaySetting.backgroundEffectType) {
        BackgroundEffectType.BLUR -> hazeBlur(
            input = HazeInput.Sources(hazeState),
            style = HazeBlurStyle.Material3 {
                blurRadius(12.dp)
            },
        )

        BackgroundEffectType.GLASS -> {
            val darkMode = LocalDarkMode.current
            hazeGlass(
                input = HazeInput.Sources(hazeState),
                style = GlassStyle.Material3(
                    containerColor = containerColor,
                    tint = containerColor.copy(alpha = 0.3f),
                ) {
                    appearanceResponse(dark = darkMode)
                    // Keep background text from competing with the input text.
                    optics(
                        GlassDefaults.optics.copy(
                            blurRadius = OpticalSizeValue.Fixed(16.dp),
                            depth = OpticalSizeValue.Fixed(0.5f),
                        )
                    )
                    shape(shape)
                },
            )
        }
    }
}

private fun GlassStyleScope.appearanceResponse(dark: Boolean) {
    if (dark) {
        specularIntensity(0.38f)
        edgeShadow(Color.Black.copy(alpha = 0.32f))
        ambientResponse(0.08f)
        contrast(0.08f)
        whitePoint(-0.22f)
        chromaMultiplier(1.1f)
    } else {
        specularIntensity(GlassDefaults.specularIntensity)
        edgeShadow(GlassDefaults.edgeShadow)
        ambientResponse(GlassDefaults.ambientResponse)
        contrast(GlassDefaults.contrast)
        whitePoint(GlassDefaults.whitePoint)
        chromaMultiplier(GlassDefaults.chromaMultiplier)
    }
}
