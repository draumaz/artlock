package io.github.draumaz.artlock.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.draumaz.artlock.R

// Google Sans Flex (variable font, SIL Open Font License) — the Material 3 Expressive typeface.
// One Font per weight so each weight is rendered from the matching point on the 'wght' axis.
private fun googleSansFlex(weight: FontWeight) = Font(
    resId = R.font.google_sans_flex,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

val GoogleSansFlex = FontFamily(
    listOf(
        FontWeight.Thin, FontWeight.ExtraLight, FontWeight.Light, FontWeight.Normal, FontWeight.Medium,
        FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black
    ).map(::googleSansFlex)
)

private fun TextStyle.flex() = copy(fontFamily = GoogleSansFlex)

private val baseline = Typography()

// Material 3 baseline type scale (including the emphasized expressive styles) set in Google Sans Flex
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val Typography = Typography(
    displayLarge = baseline.displayLarge.flex(),
    displayMedium = baseline.displayMedium.flex(),
    displaySmall = baseline.displaySmall.flex(),
    headlineLarge = baseline.headlineLarge.flex(),
    headlineMedium = baseline.headlineMedium.flex(),
    headlineSmall = baseline.headlineSmall.flex(),
    titleLarge = baseline.titleLarge.flex(),
    titleMedium = baseline.titleMedium.flex(),
    titleSmall = baseline.titleSmall.flex(),
    bodyLarge = baseline.bodyLarge.flex(),
    bodyMedium = baseline.bodyMedium.flex(),
    bodySmall = baseline.bodySmall.flex(),
    labelLarge = baseline.labelLarge.flex(),
    labelMedium = baseline.labelMedium.flex(),
    labelSmall = baseline.labelSmall.flex(),
    displayLargeEmphasized = baseline.displayLargeEmphasized.flex(),
    displayMediumEmphasized = baseline.displayMediumEmphasized.flex(),
    displaySmallEmphasized = baseline.displaySmallEmphasized.flex(),
    headlineLargeEmphasized = baseline.headlineLargeEmphasized.flex(),
    headlineMediumEmphasized = baseline.headlineMediumEmphasized.flex(),
    headlineSmallEmphasized = baseline.headlineSmallEmphasized.flex(),
    titleLargeEmphasized = baseline.titleLargeEmphasized.flex(),
    titleMediumEmphasized = baseline.titleMediumEmphasized.flex(),
    titleSmallEmphasized = baseline.titleSmallEmphasized.flex(),
    bodyLargeEmphasized = baseline.bodyLargeEmphasized.flex(),
    bodyMediumEmphasized = baseline.bodyMediumEmphasized.flex(),
    bodySmallEmphasized = baseline.bodySmallEmphasized.flex(),
    labelLargeEmphasized = baseline.labelLargeEmphasized.flex(),
    labelMediumEmphasized = baseline.labelMediumEmphasized.flex(),
    labelSmallEmphasized = baseline.labelSmallEmphasized.flex()
)
