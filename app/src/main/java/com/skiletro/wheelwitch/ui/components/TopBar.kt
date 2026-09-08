package com.skiletro.wheelwitch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.BuildConfig
import com.skiletro.wheelwitch.R
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val TopBarBobAmplitudeDp = 4
private const val TopBarBobPeriodMs = 3000
private const val NanosecondsPerMillisecond = 1_000_000L
private val TopBarTwoPi = (2.0 * Math.PI).toFloat()
private const val ClockRefreshMs = 60_000L
private val TopBarHorizontalPadding = 20.dp
private val TopBarVerticalPadding = 16.dp
private const val DisabledIconAlpha = 0.38f

/**
 * Top app bar shown on the Home screen.
 *
 * Renders the brand name + subtitle, a clock, and three icon buttons:
 * online menu, Mii Maker, and settings.
 *
 * [onLaunchMiiMaker] and [onOpenOnlineMenu] accept an `enabled` flag
 * that gates the corresponding button. The Mii Maker button also dims
 * its icon tint manually when disabled because the default Material
 * disabled styling is too subtle on this design.
 */
@Composable
fun TopBar(
    onOpenSettings: () -> Unit,
    onLaunchMiiMaker: () -> Unit,
    miiMakerEnabled: Boolean,
    onOpenOnlineMenu: () -> Unit,
) {
    var bobOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val periodNanos = TopBarBobPeriodMs * NanosecondsPerMillisecond
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { nowNanos ->
                val phase =
                    ((nowNanos - startNanos) % periodNanos).toFloat() / periodNanos
                bobOffset = -TopBarBobAmplitudeDp.toFloat() * sin(phase * TopBarTwoPi)
            }
        }
    }

    var onlineMenuFocused by remember { mutableStateOf(false) }
    var miiMakerFocused by remember { mutableStateOf(false) }
    var settingsFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TopBarHorizontalPadding, vertical = TopBarVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.offset(y = bobOffset.dp),
            contentAlignment = Alignment.Center
        ) {
            SparkleHat(hatSize = 38.dp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                VersionBadge()
            }
            Text(
                text = stringResource(R.string.topbar_subtitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onOpenOnlineMenu,
            modifier = Modifier
                .onFocusChanged { onlineMenuFocused = it.isFocused }
                .focusBorder(onlineMenuFocused, CircleShape)
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_dns),
                contentDescription = stringResource(R.string.cd_online_menu),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onLaunchMiiMaker,
            enabled = miiMakerEnabled,
            modifier = Modifier
                .onFocusChanged { miiMakerFocused = it.isFocused }
                .focusBorder(miiMakerFocused, CircleShape)
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_face_up),
                contentDescription = stringResource(R.string.cd_mii_maker),
                tint = if (miiMakerEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DisabledIconAlpha),
                modifier = Modifier.size(24.dp)
            )
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .onFocusChanged { settingsFocused = it.isFocused }
                .focusBorder(settingsFocused, CircleShape)
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.cd_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        ClockText()
    }
}

/** Displays the current time, refreshing once per [ClockRefreshMs]. */
@Composable
private fun ClockText() {
    var timeText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (isActive) {
            val now = LocalTime.now()
            timeText = now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            delay(ClockRefreshMs)
        }
    }

    Text(
        text = timeText,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun VersionBadge() {
    val version = BuildConfig.VERSION_NAME.substringBefore("+")
    val label: String =
        if (BuildConfig.DEBUG) stringResource(R.string.topbar_canary_version, version) else version
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
