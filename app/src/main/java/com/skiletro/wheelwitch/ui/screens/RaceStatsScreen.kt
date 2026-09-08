package com.skiletro.wheelwitch.ui.screens


import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.CountStat
import com.skiletro.wheelwitch.model.RaceStats
import com.skiletro.wheelwitch.model.WinRateStat
import com.skiletro.wheelwitch.ui.components.ErrorRetry
import com.skiletro.wheelwitch.ui.components.LoadingBox
import com.skiletro.wheelwitch.ui.components.ScreenHeader
import com.skiletro.wheelwitch.ui.theme.CtmkfFontFamily
import com.skiletro.wheelwitch.ui.theme.surfaceShape
import com.skiletro.wheelwitch.viewmodel.OnlineViewModel
import com.skiletro.wheelwitch.viewmodel.RaceStatsState

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun RaceStatsScreen(
    viewModel: OnlineViewModel,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedContentScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
) {
    val raceStatsState by viewModel.raceStatsState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val lastRefreshed = when (val s = raceStatsState) {
            is RaceStatsState.Success -> s.lastRefreshedAt
            else -> null
        }
        ScreenHeader(
            title = stringResource(R.string.race_stats_title),
            onBack = { viewModel.goBack() },
            onRefresh = { viewModel.fetchRaceStats() },
            titleModifier = com.skiletro.wheelwitch.ui.components.SharedTitleModifier(
                key = "online_title_RaceStats",
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            ),
            trailing = {
                if (lastRefreshed != null && lastRefreshed > 0) {
                    Text(
                        text = formatRelativeTime(lastRefreshed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            when (raceStatsState) {
                is RaceStatsState.Idle -> {}
                is RaceStatsState.Loading -> {
                    LoadingBox()
                }

                is RaceStatsState.Error -> {
                    ErrorRetry(
                        message = (raceStatsState as RaceStatsState.Error).message,
                        onRetry = { viewModel.fetchRaceStats() }
                    )
                }

                is RaceStatsState.Success -> {
                    StatContent((raceStatsState as RaceStatsState.Success).stats)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatContent(stats: RaceStats) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Headline stats: 3 in a row
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .focusable()
            ) {
                MiniStatCard(
                    label = stringResource(R.string.race_stats_races),
                    value = formatNumber(stats.totalRaces),
                    modifier = Modifier.weight(1f)
                )
                MiniStatCard(
                    label = stringResource(R.string.race_stats_players),
                    value = formatNumber(stats.totalPlayers),
                    modifier = Modifier.weight(1f)
                )
                stats.trackedSince?.let {
                    // take(10) extracts the YYYY-MM-DD date prefix from the ISO-8601 timestamp.
                    MiniStatCard(
                        label = stringResource(R.string.race_stats_since),
                        value = it.take(10),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Most Active Players
        if (stats.mostActivePlayers.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .animateItem()
                        .focusable()
                ) {
                    SectionHeader(stringResource(R.string.race_stats_active_players))
                    StatsCard {
                        stats.mostActivePlayers.take(5).forEachIndexed { index, player ->
                            if (index > 0) ThinDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (index < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.width(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = player.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = CtmkfFontFamily,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = formatNumber(player.raceCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top Characters / Vehicles / Combos: tabbed
        if (stats.topCharacters.isNotEmpty() || stats.topVehicles.isNotEmpty() || stats.topCombos.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .animateItem()
                        .focusable()
                ) {
                    SectionHeader(stringResource(R.string.race_stats_most_used))
                    var usageTab by remember { mutableIntStateOf(0) }
                    val usageLabels = listOf(
                        stringResource(R.string.race_stats_characters),
                        stringResource(R.string.race_stats_vehicles),
                        stringResource(R.string.race_stats_combos)
                    )
                    val usageData = listOf(stats.topCharacters, stats.topVehicles, stats.topCombos)

                    SecondaryTabRow(
                        selectedTabIndex = usageTab,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        divider = {}
                    ) {
                        usageLabels.forEachIndexed { index, label ->
                            Tab(
                                selected = usageTab == index,
                                onClick = { usageTab = index },
                                text = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (usageTab == index) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                    if (usageData[usageTab].isNotEmpty()) {
                        NamedStatList(usageData[usageTab])
                    }
                }
            }
        }

        // Win Rates: tabbed
        if (stats.topCharactersByWinRate.isNotEmpty() || stats.topVehiclesByWinRate.isNotEmpty() || stats.topCombosByWinRate.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .animateItem()
                        .focusable()
                ) {
                    SectionHeader(stringResource(R.string.race_stats_win_rates))
                    var winTab by remember { mutableIntStateOf(0) }
                    val winLabels = listOf(
                        stringResource(R.string.race_stats_characters),
                        stringResource(R.string.race_stats_vehicles),
                        stringResource(R.string.race_stats_combos)
                    )
                    val winData = listOf(
                        stats.topCharactersByWinRate,
                        stats.topVehiclesByWinRate,
                        stats.topCombosByWinRate
                    )

                    SecondaryTabRow(
                        selectedTabIndex = winTab,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        divider = {}
                    ) {
                        winLabels.forEachIndexed { index, label ->
                            Tab(
                                selected = winTab == index,
                                onClick = { winTab = index },
                                text = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (winTab == index) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                    if (winData[winTab].isNotEmpty()) {
                        WinRateList(winData[winTab])
                    }
                }
            }
        }

        // Most Popular Tracks
        if (stats.allPlayedTracks.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .animateItem()
                        .focusable()
                ) {
                    SectionHeader(stringResource(R.string.race_stats_popular_tracks))
                    StatsCard {
                        stats.allPlayedTracks.take(10).forEachIndexed { index, track ->
                            if (index > 0) ThinDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (index < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.width(24.dp)
                                )
                                Text(
                                    text = track.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatNumber(track.raceCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Activity charts: side by side
        if (stats.racesByDayOfWeek.isNotEmpty() || stats.racesByHour.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .animateItem()
                        .focusable()
                ) {
                    SectionHeader(stringResource(R.string.race_stats_activity))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (stats.racesByDayOfWeek.isNotEmpty()) {
                            DayOfWeekChart(stats.racesByDayOfWeek, modifier = Modifier.weight(1f))
                        }
                        if (stats.racesByHour.isNotEmpty()) {
                            PeakHoursChart(stats.racesByHour, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun StatsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        shape = surfaceShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun MiniStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = surfaceShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThinDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}

@Composable
private fun NamedStatList(items: List<CountStat>) {
    val maxCount = items.firstOrNull()?.raceCount ?: 1
    StatsCard {
        items.take(5).forEachIndexed { index, item ->
            if (index > 0) ThinDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (index < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = CtmkfFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    val barFraction = item.raceCount.toFloat() / maxCount
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = barFraction)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatNumber(item.raceCount),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun WinRateList(items: List<WinRateStat>) {
    StatsCard {
        items.take(5).forEachIndexed { index, item ->
            if (index > 0) ThinDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (index < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = CtmkfFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.race_stats_win_rate_format, item.winRate),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(
                            R.string.race_stats_win_count_format,
                            formatNumber(item.winCount),
                            formatNumber(item.raceCount)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DayOfWeekChart(
    days: List<com.skiletro.wheelwitch.model.CountStat>,
    modifier: Modifier = Modifier
) {
    val maxCount = (days.maxOfOrNull { it.raceCount } ?: 0).coerceAtLeast(1)
    StatsCard(modifier = modifier) {
        days.forEach { day ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = day.name.take(3),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(28.dp)
                )
                val barFraction = (day.raceCount.toFloat() / maxCount).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { barFraction },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                )
            }
        }
    }
}

@Composable
private fun PeakHoursChart(
    hours: List<com.skiletro.wheelwitch.model.HourStat>,
    modifier: Modifier = Modifier
) {
    val maxCount = (hours.maxOfOrNull { it.raceCount } ?: 0).coerceAtLeast(1)
    StatsCard(modifier = modifier) {
        hours.filter { it.hour % 3 == 0 }.forEach { hour ->
            val label = when (hour.hour) {
                0 -> "12a"
                12 -> "12p"
                in 1..11 -> "${hour.hour}a"
                else -> "${hour.hour - 12}p"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(28.dp)
                )
                val barFraction = (hour.raceCount.toFloat() / maxCount).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { barFraction },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                )
            }
        }
    }
}

@Composable
private fun formatNumber(n: Int): String {
    return when {
        n >= 1_000_000 -> stringResource(R.string.race_stats_number_m, n / 1_000_000f)
        n >= 1_000 -> stringResource(R.string.race_stats_number_k, n / 1_000f)
        else -> n.toString()
    }
}

@Composable
private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> stringResource(R.string.race_stats_just_now)
        minutes < 60 -> stringResource(R.string.race_stats_minutes_ago, minutes)
        hours < 24 -> stringResource(R.string.race_stats_hours_ago, hours)
        else -> stringResource(R.string.race_stats_days_ago, days)
    }
}
