package com.skiletro.wheelwitch.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic colors for OK/error indicators and cards. */
@Immutable
data class StatusColors(
    val okContainer: Color,
    val onOkContainer: Color,
    val ok: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val error: Color
)

val LightStatusColors = StatusColors(
    okContainer = Color(0xFF1B5E20),
    onOkContainer = Color.White,
    ok = Color(0xFF4CAF50),
    errorContainer = Color(0xFFB71C1C),
    onErrorContainer = Color.White,
    error = Color(0xFFEF5350)
)

val DarkStatusColors = StatusColors(
    okContainer = Color(0xFF1B5E20),
    onOkContainer = Color.White,
    ok = Color(0xFF4CAF50),
    errorContainer = Color(0xFFB71C1C),
    onErrorContainer = Color.White,
    error = Color(0xFFEF5350)
)

/**
 * Composition local providing the active [StatusColors] for the current
 * theme. Defaults to [LightStatusColors] when no [WheelWitchTheme] is in
 * scope (e.g. previews).
 */
val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
