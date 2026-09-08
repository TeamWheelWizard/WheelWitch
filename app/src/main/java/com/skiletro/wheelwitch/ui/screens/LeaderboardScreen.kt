package com.skiletro.wheelwitch.ui.screens


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.LeaderboardEntry
import com.skiletro.wheelwitch.ui.components.EmptyState
import com.skiletro.wheelwitch.ui.components.ErrorRetry
import com.skiletro.wheelwitch.ui.components.FocusableSurface
import com.skiletro.wheelwitch.ui.components.LoadingBox
import com.skiletro.wheelwitch.ui.components.MiiFace
import com.skiletro.wheelwitch.ui.components.ScreenHeader
import com.skiletro.wheelwitch.ui.theme.CtmkfFontFamily
import com.skiletro.wheelwitch.ui.theme.chipShape
import com.skiletro.wheelwitch.viewmodel.LeaderboardState
import com.skiletro.wheelwitch.viewmodel.OnlineViewModel

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun LeaderboardScreen(
    viewModel: OnlineViewModel,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedContentScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
) {
    val leaderboardState by viewModel.leaderboardState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ScreenHeader(
            title = stringResource(R.string.leaderboard_title),
            onBack = { viewModel.goBack() },
            onRefresh = { viewModel.fetchLeaderboard() },
            titleModifier = com.skiletro.wheelwitch.ui.components.SharedTitleModifier(
                key = "online_title_Leaderboard",
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            when (leaderboardState) {
                is LeaderboardState.Idle -> {}
                is LeaderboardState.Loading -> {
                    LoadingBox()
                }

                is LeaderboardState.Error -> {
                    ErrorRetry(
                        message = (leaderboardState as LeaderboardState.Error).message,
                        onRetry = { viewModel.fetchLeaderboard() }
                    )
                }

                is LeaderboardState.Success -> {
                    val state = leaderboardState as LeaderboardState.Success
                    if (state.entries.isEmpty()) {
                        EmptyState(message = stringResource(R.string.leaderboard_no_entries))
                    } else {
                        LeaderboardList(
                            entries = state.entries,
                            hasMore = state.hasMore,
                            onLoadMore = { viewModel.fetchLeaderboard() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardList(
    entries: List<LeaderboardEntry>,
    hasMore: Boolean,
    onLoadMore: () -> Unit
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var hasRequestedFocus by remember { mutableStateOf(false) }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && hasMore &&
                    lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    LaunchedEffect(entries) {
        if (entries.isNotEmpty() && !hasRequestedFocus) {
            focusRequester.requestFocus()
            hasRequestedFocus = true
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .focusRequester(focusRequester),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(entries, key = { "${it.rank}_${it.friendCode}" }) { entry ->
            LeaderboardRow(entry = entry, modifier = Modifier.animateItem())
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        }
        if (hasMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(entry: LeaderboardEntry, modifier: Modifier = Modifier) {
    val shape = chipShape
    FocusableSurface(
        modifier = modifier.fillMaxWidth(),
        onClick = { },
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.leaderboard_rank_format, entry.rank),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (entry.rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(44.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            MiiFace(
                imageBase64 = entry.miiImageBase64,
                miiDataBase64 = entry.player.miiData,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.player.name ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = CtmkfFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.friendCode,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${entry.player.vr}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.leaderboard_vr_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
