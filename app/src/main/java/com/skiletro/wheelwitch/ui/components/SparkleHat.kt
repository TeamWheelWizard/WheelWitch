package com.skiletro.wheelwitch.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.ui.theme.WheelWitchPreviewTheme
import kotlin.math.cos
import kotlin.math.sin

private const val SPARKLE_COUNT = 6
private const val SPARKLE_PERIOD_MS = 3000
private val SPARKLE_INDICES = listOf(0, 3, 4)

/** Extra canvas space beyond [hatSize] so sparkles at the orbit edge are not clipped. */
private val SparklePaddingDp = 30.dp

/** Sparkle orbit radius as a fraction of hatSize. */
private const val SparkleOrbitFactor = 0.58f
private val SparkleSizeDp = 3.dp
private val SparkleStrokeDp = 2.dp

// Three-phase alpha curve: fade in from 0 to 1 over [0, SparkleFadeInEnd],
// hold at 1 over [SparkleFadeInEnd, SparkleFadeOutStart], fade out to 0
// over [SparkleFadeOutStart, 1].
private const val SparkleFadeInEnd = 0.35f
private const val SparkleFadeOutStart = 0.65f

/**
 * Renders the wizard hat icon with an animated sparkle overlay.
 *
 * The [tint] controls the sparkle color (with full alpha) and is also used
 * for the hat icon. The sparkles orbit at a fixed proportion of the [hatSize].
 * When the system animator scale is 0 (reduce-motion setting), the sparkles
 * are drawn statically instead of animating.
 *
 * Apply external `Modifier.offset(y = bob.dp)` to add a vertical bob if
 * desired. The bob animation is not encapsulated here so callers can
 * combine it with other offsets (e.g. a launch animation).
 */
@Composable
fun SparkleHat(
    hatSize: Dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    val reduceMotion =
        Settings.Global.getFloat(
            LocalContext.current.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f

    val sparklePhase: Float by
        if (reduceMotion) {
            remember { mutableStateOf(0f) }
        } else {
            val infiniteTransition = rememberInfiniteTransition(label = "sparkle")
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = SPARKLE_PERIOD_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "sparklePhase"
            )
        }

    Box(
        modifier = modifier
            .size(hatSize + SparklePaddingDp)
            .drawBehind {
                val radius = (hatSize.value * SparkleOrbitFactor).dp.toPx()
                val sparkleSize = SparkleSizeDp.toPx()
                val strokeW = SparkleStrokeDp.toPx()
                val centerX = size.width / 2
                val centerY = size.height / 2

                for (i in SPARKLE_INDICES) {
                    val rawPhase = (sparklePhase + i.toFloat() / SPARKLE_COUNT) % 1f
                    val alpha = when {
                        rawPhase < SparkleFadeInEnd -> rawPhase / SparkleFadeInEnd
                        rawPhase < SparkleFadeOutStart -> 1f
                        else -> 1f - (rawPhase - SparkleFadeOutStart) / (1f - SparkleFadeOutStart)
                    }

                    val angle = i.toFloat() / SPARKLE_COUNT * 2f * Math.PI.toFloat()
                    val x = centerX + radius * cos(angle)
                    val y = centerY + radius * sin(angle)

                    drawLine(
                        tint.copy(alpha = alpha),
                        Offset(x - sparkleSize, y),
                        Offset(x + sparkleSize, y),
                        strokeW
                    )
                    drawLine(
                        tint.copy(alpha = alpha),
                        Offset(x, y - sparkleSize),
                        Offset(x, y + sparkleSize),
                        strokeW
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_hat_wizard),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(hatSize)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SparkleHatPreview() {
    WheelWitchPreviewTheme {
        SparkleHat(hatSize = 64.dp)
    }
}
