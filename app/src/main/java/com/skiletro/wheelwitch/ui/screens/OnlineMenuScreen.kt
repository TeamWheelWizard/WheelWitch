package com.skiletro.wheelwitch.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.ServerConnectivity
import com.skiletro.wheelwitch.ui.components.FocusableSurface
import com.skiletro.wheelwitch.ui.components.ScreenHeader
import com.skiletro.wheelwitch.ui.theme.statusColors
import com.skiletro.wheelwitch.ui.theme.surfaceShape
import com.skiletro.wheelwitch.viewmodel.OnlineMenuPage
import com.skiletro.wheelwitch.viewmodel.OnlineViewModel
import com.skiletro.wheelwitch.viewmodel.RoomsState

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun OnlineMenuScreen(
    viewModel: OnlineViewModel,
    onClose: () -> Unit
) {
    val currentPage by viewModel.currentPage.collectAsState()

    BackHandler {
        if (currentPage != OnlineMenuPage.Hub) {
            viewModel.goBack()
        } else {
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SharedTransitionLayout {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    (slideInHorizontally { it } + fadeIn()).togetherWith(slideOutHorizontally { -it } + fadeOut())
                },
                label = "online_pages"
            ) { page ->
                when (page) {
                    OnlineMenuPage.Hub -> HubPage(
                        viewModel = viewModel,
                        onClose = onClose,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedContentScope = this,
                    )

                    OnlineMenuPage.Rooms -> {
                        val roomsState by viewModel.roomsState.collectAsState()
                        RoomsScreen(
                            roomsState = roomsState,
                            onRefresh = { viewModel.fetchRooms() },
                            onClose = { viewModel.goBack() },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this,
                        )
                    }

                    OnlineMenuPage.Leaderboard -> LeaderboardScreen(
                        viewModel,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedContentScope = this,
                    )

                    OnlineMenuPage.Health -> HealthScreen(
                        viewModel,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedContentScope = this,
                    )

                    OnlineMenuPage.RaceStats -> RaceStatsScreen(
                        viewModel,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedContentScope = this,
                    )

                    OnlineMenuPage.TimeTrial -> TimeTrialScreen(
                        viewModel,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedContentScope = this,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HubPage(
    viewModel: OnlineViewModel,
    onClose: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedVisibilityScope,
) {
    val roomsState by viewModel.roomsState.collectAsState()
    val serverConnectivity =
        (roomsState as? RoomsState.Success)?.serverConnectivity ?: ServerConnectivity.Unknown
    val isOnline = serverConnectivity is ServerConnectivity.Online

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        ScreenHeader(
            title = stringResource(R.string.online_menu_title),
            onBack = onClose
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HubOption(
                icon = ImageVector.vectorResource(R.drawable.ic_meeting_room),
                title = stringResource(R.string.online_rooms),
                description = stringResource(R.string.online_rooms_desc),
                onClick = { viewModel.navigateTo(OnlineMenuPage.Rooms) },
                enabled = isOnline,
                titleSharedKey = OnlineMenuPage.Rooms.titleSharedKey,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
            HubOption(
                icon = ImageVector.vectorResource(R.drawable.ic_leaderboard),
                title = stringResource(R.string.online_leaderboard),
                description = stringResource(R.string.online_leaderboard_desc),
                onClick = { viewModel.navigateTo(OnlineMenuPage.Leaderboard) },
                enabled = isOnline,
                titleSharedKey = OnlineMenuPage.Leaderboard.titleSharedKey,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
            HubOption(
                icon = ImageVector.vectorResource(R.drawable.ic_favorite),
                title = stringResource(R.string.online_server_health),
                description = stringResource(R.string.online_server_health_desc),
                onClick = { viewModel.navigateTo(OnlineMenuPage.Health) },
                titleSharedKey = OnlineMenuPage.Health.titleSharedKey,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
            HubOption(
                icon = ImageVector.vectorResource(R.drawable.ic_finance),
                title = stringResource(R.string.online_race_stats),
                description = stringResource(R.string.online_race_stats_desc),
                onClick = { viewModel.navigateTo(OnlineMenuPage.RaceStats) },
                enabled = isOnline,
                titleSharedKey = OnlineMenuPage.RaceStats.titleSharedKey,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
            HubOption(
                icon = ImageVector.vectorResource(R.drawable.ic_motorcycle),
                title = stringResource(R.string.online_time_trials),
                description = stringResource(R.string.online_time_trials_desc),
                onClick = { viewModel.navigateTo(OnlineMenuPage.TimeTrial) },
                enabled = isOnline,
                titleSharedKey = OnlineMenuPage.TimeTrial.titleSharedKey,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HubOption(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    titleSharedKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedContentScope: AnimatedVisibilityScope? = null,
) {
    val shape = surfaceShape
    val baseColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    FocusableSurface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = baseColor
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = 0.38f
                        ),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(
                        alpha = 0.38f
                    ),
                    modifier = com.skiletro.wheelwitch.ui.components.SharedTitleModifier(
                        key = titleSharedKey ?: "",
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                    )
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = 0.38f
                    )
                )
            }
            if (!enabled) {
                Text(
                    text = stringResource(R.string.online_soon),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}
