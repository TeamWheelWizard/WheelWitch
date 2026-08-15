package com.skiletro.wheelwitch.ui.screens


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.Room
import com.skiletro.wheelwitch.ui.components.FocusableSurface
import com.skiletro.wheelwitch.ui.components.PrimaryActionButton
import com.skiletro.wheelwitch.ui.components.ScreenHeader
import com.skiletro.wheelwitch.ui.theme.CtmkfFontFamily
import com.skiletro.wheelwitch.ui.theme.chipShape
import com.skiletro.wheelwitch.ui.theme.statusColors
import com.skiletro.wheelwitch.viewmodel.RoomsState

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun RoomsScreen(
    roomsState: RoomsState,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedContentScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
) {
    BackHandler(onBack = onClose)

    val rooms = (roomsState as? RoomsState.Success)?.rooms ?: emptyList()
    var selectedRoomId by remember { mutableStateOf<String?>(null) }
    val selectedRoom = selectedRoomId?.let { id -> rooms.find { it.id == id } }
    val listFocusRequester = remember { FocusRequester() }
    var hasRequestedFocus by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ScreenHeader(
            title = stringResource(R.string.rooms_title),
            onBack = onClose,
            onRefresh = onRefresh,
            titleModifier = com.skiletro.wheelwitch.ui.components.SharedTitleModifier(
                key = "online_title_Rooms",
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            when (roomsState) {
                is RoomsState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is RoomsState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = roomsState.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        PrimaryActionButton(
                            text = stringResource(R.string.action_retry),
                            onClick = onRefresh
                        )
                    }
                }

                is RoomsState.Success -> {
                    if (rooms.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.rooms_no_rooms),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                PrimaryActionButton(
                                    text = stringResource(R.string.rooms_refresh),
                                    onClick = onRefresh
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(0.35f)
                                    .fillMaxHeight()
                                    .focusRequester(listFocusRequester),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(rooms, key = { it.id }) { room ->
                                    RoomListItem(
                                        room = room,
                                        isSelected = room.id == selectedRoomId,
                                        onClick = {
                                            selectedRoomId = room.id
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .animateItem()
                                    )
                                }
                            }

                            VerticalDivider()

                            Box(
                                modifier = Modifier
                                    .weight(0.65f)
                                    .fillMaxHeight()
                            ) {
                                if (selectedRoom != null) {
                                    RoomDetail(selectedRoom)
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.rooms_select_a_room),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is RoomsState.Idle -> {}
            }
        }
    }

    LaunchedEffect(rooms) {
        if (rooms.isNotEmpty() && !hasRequestedFocus) {
            listFocusRequester.requestFocus()
            hasRequestedFocus = true
        }
    }
}

@Composable
fun RoomListItem(
    room: Room,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableSurface(
        modifier = modifier.defaultMinSize(minHeight = 56.dp),
        onClick = onClick,
        selected = isSelected,
        shape = chipShape
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = room.players.firstOrNull()?.name
                            ?: stringResource(R.string.status_empty),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = CtmkfFontFamily
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.rooms_player_count,
                            room.players.size,
                            room.players.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (room.isJoinable) {
                    Surface(
                        color = statusColors().ok,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.rooms_open),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
