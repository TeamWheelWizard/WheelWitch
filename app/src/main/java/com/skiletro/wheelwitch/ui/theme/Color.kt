package com.skiletro.wheelwitch.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Per-theme accents: `80` denotes dark-theme variants and `40` light-theme variants.
 * `On*` colors provide foregrounds for their matching accents; other roles are set in the theme.
 */

// Hex palette (dark): the default brand theme
val Purple80 = Color(0xFFD0B0FF)
val PurpleGrey80 = Color(0xFFB8A0CC)
val LightPurple80 = Color(0xFFFFB3D0)
val OnPurple80 = Color(0xFF3D0080)

// Hex palette (light)
val Purple40 = Color(0xFF6A1B9A)
val PurpleGrey40 = Color(0xFF7B5293)
val LightPurple40 = Color(0xFF942D66)
val OnPurple40 = Color.White

// Swamp palette (dark)
val SwampGreen80 = Color(0xFFA5D6A7)
val SwampGreenGrey80 = Color(0xFF81C784)
val SwampLightGreen80 = Color(0xFFB2FFB2)
val OnSwampGreen80 = Color(0xFF003300)

// Swamp palette (light)
val SwampGreen40 = Color(0xFF2E7D32)
val SwampGreenGrey40 = Color(0xFF388E3C)
val SwampLightGreen40 = Color(0xFF1B5E20)
val OnSwampGreen40 = Color.White

// Wizard palette (dark): based on the WheelWizard project palette
val WizardTeal80 = Color(0xFF00A488)
val WizardTealGrey80 = Color(0xFF4DB6AC)

// Wizard's tertiary is a neutral grey rather than a light accent.
val WizardNeutral80 = Color(0xFF8B91A5)
val OnWizardTeal80 = Color(0xFF00332A)

// Wizard palette (light)
val WizardTeal40 = Color(0xFF00897B)
val WizardTealGrey40 = Color(0xFF26A69A)
val WizardNeutral40 = Color(0xFF5C5F73)
val OnWizardTeal40 = Color.White

// Catppuccin palette (dark): Mocha
val CatppuccinMauve80 = Color(0xFFCBA6F7)
val CatppuccinLavender80 = Color(0xFFB4BEFE)
val CatppuccinSky80 = Color(0xFF89DCEB)
val OnCatppuccinMauve80 = Color(0xFF2D1450)

// Catppuccin palette (light): Latte
val CatppuccinMauve40 = Color(0xFF8839EF)
val CatppuccinLavender40 = Color(0xFF7287FD)
val CatppuccinSky40 = Color(0xFF04A5E5)
val OnCatppuccinMauve40 = Color.White

// OLED overrides, applied on top of any dark scheme when ThemeMode.Oled is active
val OledSurfaceVariant = Color(0xFF121212)
val OledOnBackground = Color(0xFFE0E0E0)
val OledOnSurface = Color(0xFFE0E0E0)
val OledOnSurfaceVariant = Color(0xFFB0B0B0)
