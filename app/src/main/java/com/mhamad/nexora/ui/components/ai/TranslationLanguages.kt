package com.mhamad.nexora.ui.components.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import com.mhamad.nexora.R
import java.util.Locale

val TranslationLanguages: List<Locale> = listOf(
    Locale.ENGLISH,
    Locale.JAPANESE,
    Locale.KOREAN,
    Locale.FRENCH,
    Locale.GERMAN,
    Locale.ITALIAN,
    Locale.forLanguageTag("es-ES"),
)

@Composable
fun translationLanguageName(locale: Locale): String = when (locale) {
    Locale.ENGLISH -> stringResource(R.string.language_english)
    Locale.JAPANESE -> stringResource(R.string.language_japanese)
    Locale.KOREAN -> stringResource(R.string.language_korean)
    Locale.FRENCH -> stringResource(R.string.language_french)
    Locale.GERMAN -> stringResource(R.string.language_german)
    Locale.ITALIAN -> stringResource(R.string.language_italian)
    Locale.forLanguageTag("es-ES") -> stringResource(R.string.language_spanish)
    else -> locale.getDisplayLanguage(LocalLocale.current.platformLocale)
}
