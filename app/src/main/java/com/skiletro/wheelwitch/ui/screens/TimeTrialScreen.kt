package com.skiletro.wheelwitch.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.TimeTrialSubmission
import com.skiletro.wheelwitch.model.TimeTrialTrack
import com.skiletro.wheelwitch.ui.components.EmptyState
import com.skiletro.wheelwitch.ui.components.ErrorRetry
import com.skiletro.wheelwitch.ui.components.FocusableSurface
import com.skiletro.wheelwitch.ui.components.LoadingBox
import com.skiletro.wheelwitch.ui.components.ScreenHeader
import com.skiletro.wheelwitch.ui.components.VerticalDivider
import com.skiletro.wheelwitch.ui.theme.CtmkfFontFamily
import com.skiletro.wheelwitch.ui.theme.chipShape
import com.skiletro.wheelwitch.ui.theme.surfaceShape
import com.skiletro.wheelwitch.viewmodel.OnlineMenuPage
import com.skiletro.wheelwitch.viewmodel.OnlineViewModel
import com.skiletro.wheelwitch.viewmodel.TimeTrialState
import com.skiletro.wheelwitch.viewmodel.TrackLeaderboardState

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun TimeTrialScreen(
    viewModel: OnlineViewModel,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedContentScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
) {
    val ttState by viewModel.ttState.collectAsState()
    val trackLeaderboardState by viewModel.trackLeaderboardState.collectAsState()
    val selectedTrackId by viewModel.selectedTrackId.collectAsState()
    val cc by viewModel.cc.collectAsState()
    val glitchAllowed by viewModel.glitchAllowed.collectAsState()

    BackHandler(onBack = { viewModel.goBack() })

    val tracks = (ttState as? TimeTrialState.Success)?.tracks ?: emptyList()
    var searchQuery by remember { mutableStateOf("") }
    val filteredTracks by remember(tracks, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) tracks
            else tracks.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }
    var hasRequestedFocus by remember { mutableStateOf(false) }
    val listFocusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ScreenHeader(
            title = stringResource(R.string.time_trial_title),
            onBack = { viewModel.goBack() },
            onRefresh = { viewModel.fetchTracks() },
            titleModifier = com.skiletro.wheelwitch.ui.components.SharedTitleModifier(
                key = OnlineMenuPage.TimeTrial.titleSharedKey,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            when (ttState) {
                is TimeTrialState.Loading -> {
                    LoadingBox()
                }

                is TimeTrialState.Error -> {
                    ErrorRetry(
                        message = (ttState as TimeTrialState.Error).message,
                        onRetry = { viewModel.fetchTracks() }
                    )
                }

                is TimeTrialState.Success -> {
                    if (tracks.isEmpty()) {
                        EmptyState(
                            message = stringResource(R.string.time_trial_no_tracks),
                            actionLabel = stringResource(R.string.action_retry),
                            onAction = { viewModel.fetchTracks() }
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(0.35f)
                                    .fillMaxHeight()
                            ) {
                                val searchIconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = {
                                        Text(
                                            text = stringResource(R.string.time_trial_search_hint),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = ImageVector.vectorResource(R.drawable.ic_search),
                                            contentDescription = null,
                                            tint = searchIconTint,
                                        )
                                    },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(
                                                    imageVector = ImageVector.vectorResource(R.drawable.ic_close),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {}),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        cursorColor = MaterialTheme.colorScheme.primary,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    ),
                                    shape = surfaceShape,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                )

                                if (filteredTracks.isEmpty() && searchQuery.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.time_trial_no_results),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .focusRequester(listFocusRequester),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        items(filteredTracks, key = { it.id }) { track ->
                                            TrackListItem(
                                                track = track,
                                                isSelected = track.id == selectedTrackId,
                                                onClick = { viewModel.selectTrack(track.id) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .animateItem(),
                                            )
                                        }
                                    }
                                }
                            }

                            VerticalDivider()

                            Box(
                                modifier = Modifier
                                    .weight(0.65f)
                                    .fillMaxHeight()
                            ) {
                                when {
                                    selectedTrackId == null -> {
                                        EmptyState(
                                            message = stringResource(R.string.time_trial_select_track)
                                        )
                                    }

                                    trackLeaderboardState is TrackLeaderboardState.Loading -> {
                                        LoadingBox()
                                    }

                                    trackLeaderboardState is TrackLeaderboardState.Error -> {
                                        ErrorRetry(
                                            message = (trackLeaderboardState as TrackLeaderboardState.Error).message,
                                            onRetry = { viewModel.fetchTrackLeaderboard() }
                                        )
                                    }

                                    trackLeaderboardState is TrackLeaderboardState.Success -> {
                                        val lbState = trackLeaderboardState as TrackLeaderboardState.Success
                                        TimeTrialLeaderboardPanel(
                                            submissions = lbState.submissions,
                                            hasMore = lbState.currentPage < lbState.totalPages,
                                            fastestLapDisplay = lbState.fastestLapDisplay,
                                            cc = cc,
                                            glitchAllowed = glitchAllowed,
                                            onCcChange = { viewModel.setCc(it) },
                                            onGlitchToggle = { viewModel.setGlitchAllowed(it) },
                                            onLoadMore = { viewModel.fetchMoreSubmissions() },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is TimeTrialState.Idle -> {}
            }
        }
    }

    LaunchedEffect(tracks) {
        if (tracks.isNotEmpty() && !hasRequestedFocus) {
            listFocusRequester.requestFocus()
            hasRequestedFocus = true
        }
    }
}

@Composable
private fun TrackListItem(
    track: TimeTrialTrack,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        onClick = onClick,
        selected = isSelected,
        shape = chipShape,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = CtmkfFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (track.category == "custom")
                        stringResource(R.string.time_trial_category_custom)
                    else
                        stringResource(R.string.time_trial_category_retro),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TimeTrialLeaderboardPanel(
    submissions: List<TimeTrialSubmission>,
    hasMore: Boolean,
    fastestLapDisplay: String?,
    cc: Int,
    glitchAllowed: Boolean,
    onCcChange: (Int) -> Unit,
    onGlitchToggle: (Boolean) -> Unit,
    onLoadMore: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        FilterBar(
            cc = cc,
            glitchAllowed = glitchAllowed,
            onCcChange = onCcChange,
            onGlitchToggle = onGlitchToggle,
        )

        if (submissions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.time_trial_no_submissions),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LeaderboardList(
                submissions = submissions,
                hasMore = hasMore,
                fastestLapDisplay = fastestLapDisplay,
                onLoadMore = onLoadMore,
            )
        }
    }
}

@Composable
private fun FilterBar(
    cc: Int,
    glitchAllowed: Boolean,
    onCcChange: (Int) -> Unit,
    onGlitchToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(150, 200).forEach { value ->
            FilterChip(
                text = stringResource(R.string.time_trial_cc_format, value),
                selected = cc == value,
                onClick = { onCcChange(value) },
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        FilterChip(
            text = stringResource(R.string.time_trial_glitch_allowed),
            selected = glitchAllowed,
            onClick = { onGlitchToggle(true) },
        )
        FilterChip(
            text = stringResource(R.string.time_trial_no_glitch),
            selected = !glitchAllowed,
            onClick = { onGlitchToggle(false) },
        )
    }
}

@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant

    val contentColor = if (selected)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = chipShape,
        color = containerColor,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LeaderboardList(
    submissions: List<TimeTrialSubmission>,
    hasMore: Boolean,
    fastestLapDisplay: String?,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()

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

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(submissions, key = { it.id }) { submission ->
            SubmissionRow(
                submission = submission,
                fastestLapDisplay = fastestLapDisplay,
                modifier = Modifier.animateItem(),
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
        }
        if (hasMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SubmissionRow(
    submission: TimeTrialSubmission,
    fastestLapDisplay: String?,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        modifier = modifier.fillMaxWidth(),
        onClick = { },
        shape = chipShape,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = submission.rank
              ?.let { stringResource(R.string.time_trial_rank_format, it) }
              ?: stringResource(R.string.time_trial_rank_placeholder),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (submission.rank != null && submission.rank <= 3)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(32.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = submission.playerName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = CtmkfFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (submission.miiName != null) {
                    Text(
                        text = submission.miiName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = submission.finishTimeDisplay,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (submission.fastestLapDisplay != null) {
                    Text(
                        text = submission.fastestLapDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
