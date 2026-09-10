package com.skiletro.wheelwitch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.provider.Settings
import com.skiletro.wheelwitch.ui.theme.WheelWitchPreviewTheme

/**
 * Animated status indicator dot.
 *
 * - The fill color smoothly transitions to [target] over [transitionMs].
 * - When [pulse] is true, the dot pulses (alpha 0.55→1.0 and scale
 *   0.85→1.15) on a 1100ms reverse cycle for a clear "alive" feel.
 */
@Composable
fun PulsingDot(
    target: Color,
    pulse: Boolean = false,
    sizeDp: Dp = 10.dp,
    transitionMs: Int = 400,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = transitionMs, easing = FastOutSlowInEasing),
        label = "dot_color"
    )

    val reduceMotion =
        Settings.Global.getFloat(
            LocalContext.current.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    val pulsing = pulse && !reduceMotion

    val pulseFraction by
        if (pulsing) {
            val transition = rememberInfiniteTransition(label = "dot_pulse")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_pulse_fraction"
            )
        } else {
            remember { mutableStateOf(0f) }
        }

    val alpha = if (pulsing) 0.55f + pulseFraction * 0.45f else 1f
    val scale = if (pulsing) 0.85f + pulseFraction * 0.3f else 1f

    Box(
        modifier = modifier
            .size(sizeDp)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

@Preview(showBackground = true)
@Composable
private fun PulsingDotPreview() {
    WheelWitchPreviewTheme {
        PulsingDot(target = Color(0xFF22C55E), pulse = true)
    }
}
