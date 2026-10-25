package com.skiletro.wheelwitch.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp
import com.skiletro.wheelwitch.R

/**
 * Display font used for player names, race numbers, and other short
 * numeric/branded text. Applied per-call-site via `fontFamily =
 * CtmkfFontFamily` rather than wired into [Typography], because
 * [Typography] intentionally uses [FontFamily.Default] for body text
 * to keep long-form copy readable.
 */
val CtmkfFontFamily = FontFamily(Font(R.font.ctmkf))

private fun tnum(style: TextStyle): TextStyle =
    style.copy(fontFeatureSettings = "tnum")

private fun heading(style: TextStyle): TextStyle =
    tnum(style).copy(lineBreak = LineBreak.Heading)

private fun paragraph(style: TextStyle): TextStyle =
    tnum(style).copy(lineBreak = LineBreak.Paragraph)

private val wheelWitchBaseTypography: Typography = Typography()

/** Material typography with tabular digits and heading/paragraph-specific line-breaking rules. */
val Typography: Typography = wheelWitchBaseTypography.copy(
    displayLarge = heading(wheelWitchBaseTypography.displayLarge),
    displayMedium = heading(wheelWitchBaseTypography.displayMedium),
    displaySmall = heading(wheelWitchBaseTypography.displaySmall),
    headlineLarge = heading(wheelWitchBaseTypography.headlineLarge),
    headlineMedium = heading(wheelWitchBaseTypography.headlineMedium),
    headlineSmall = heading(wheelWitchBaseTypography.headlineSmall),
    titleLarge = heading(wheelWitchBaseTypography.titleLarge),
    titleMedium = heading(wheelWitchBaseTypography.titleMedium),
    titleSmall = heading(wheelWitchBaseTypography.titleSmall),
    bodyLarge = paragraph(wheelWitchBaseTypography.bodyLarge),
    bodyMedium = paragraph(wheelWitchBaseTypography.bodyMedium),
    bodySmall = paragraph(wheelWitchBaseTypography.bodySmall),
    labelLarge = paragraph(wheelWitchBaseTypography.labelLarge),
    labelMedium = paragraph(wheelWitchBaseTypography.labelMedium),
    labelSmall = paragraph(wheelWitchBaseTypography.labelSmall),
)
