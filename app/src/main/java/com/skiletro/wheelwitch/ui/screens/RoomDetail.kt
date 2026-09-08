package com.skiletro.wheelwitch.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.Player
import com.skiletro.wheelwitch.model.Room
import com.skiletro.wheelwitch.ui.components.MiiFace
import com.skiletro.wheelwitch.ui.theme.CtmkfFontFamily
import com.skiletro.wheelwitch.ui.theme.WheelWitchPreviewTheme
import com.skiletro.wheelwitch.ui.theme.chipShape
import com.skiletro.wheelwitch.ui.theme.statusColors

@Composable
fun RoomDetail(room: Room) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.rooms_player_count,
                            room.players.size,
                            room.players.size
                        ),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (room.isJoinable) {
                        Surface(
                            color = statusColors().okContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.rooms_joinable),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                color = statusColors().onOkContainer,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                if (room.trackName != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = room.trackName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.rooms_meta_format, room.id, room.roomType),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.rooms_meta_vr_format,
                        room.averageVR,
                        if (room.isPublic) stringResource(R.string.rooms_visibility_public) else stringResource(
                            R.string.rooms_visibility_private
                        )
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.rooms_players_label),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        items(room.players, key = { "${room.id}_${it.friendCode}" }) { player ->
            MiiPlayerCard(player = player)
        }
    }
}

@Composable
fun MiiPlayerCard(player: Player) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = chipShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (player.mii?.data != null) {
                MiiFace(
                    imageBase64 = null,
                    miiDataBase64 = player.mii.data,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = player.name,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = CtmkfFontFamily
                        )
                        if (player.mii?.name != null && player.mii.name != player.name) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    R.string.rooms_quoted_name_format,
                                    player.mii.name
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = CtmkfFontFamily
                            )
                        }
                    }
                    if (player.isOpenHost) {
                        Text(
                            text = stringResource(R.string.rooms_host),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.rooms_player_meta_format,
                        player.friendCode,
                        player.vr,
                        player.br
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val sampleRoom = Room(
    id = "1",
    players = listOf(
        Player(
            name = "Hosty",
            friendCode = "9876-5432-1098",
            vr = 7200,
            br = 4000,
            isOpenHost = true,
            mii = null,
        ),
        Player(
            name = "Jamie",
            friendCode = "1234-5678-9012",
            vr = 5000,
            br = 3000,
            isOpenHost = false,
            mii = null,
        ),
    ),
    averageVR = 6100,
    trackName = "GCN Peach Beach",
    roomType = "FFA",
    isPublic = true,
    isJoinable = true,
)

@Preview(showBackground = true, widthDp = 400, heightDp = 500)
@Composable
private fun RoomDetailPreview() {
    WheelWitchPreviewTheme {
        RoomDetail(room = sampleRoom)
    }
}
