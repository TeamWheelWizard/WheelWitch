package com.skiletro.wheelwitch.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dontsaybojio.rollingnumbers.RollingNumbers
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.RANK_NAMES
import com.skiletro.wheelwitch.model.RANK_THRESH
import com.skiletro.wheelwitch.model.ScoreResult
import com.skiletro.wheelwitch.model.rankFromScore
import com.skiletro.wheelwitch.model.wouldBeRank
import com.skiletro.wheelwitch.model.wouldBeScore

private fun rankIconRes(rank: Int): Int? = when (rank) {
  1 -> R.drawable.ic_rank_e
  2 -> R.drawable.ic_rank_d
  3 -> R.drawable.ic_rank_c
  4 -> R.drawable.ic_rank_b
  5 -> R.drawable.ic_rank_a
  6 -> R.drawable.ic_rank_1star
  7 -> R.drawable.ic_rank_2star
  8 -> R.drawable.ic_rank_3star
  9 -> R.drawable.ic_rank_crown
  else -> null
}

private fun Modifier.badgeIcon(compact: Boolean): Modifier =
  size(width = if (compact) 32.dp else 40.dp, height = if (compact) 32.dp else 40.dp)

private fun Modifier.progressBar(compact: Boolean): Modifier =
  width(if (compact) 40.dp else 50.dp).height(4.dp)

@Composable
fun RankBadge(
  result: ScoreResult?,
  compact: Boolean = false,
  compactFontSize: TextUnit = TextUnit.Unspecified
) {
  if (result == null) return

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier =
      Modifier.widthIn(
        min = if (compact) 56.dp else 70.dp,
        max = if (compact) 80.dp else 96.dp,
      ),
  ) {
    if (!result.meetsRaceReq) {
      LockedBadge(result, compact, compactFontSize)
    } else {
      PopulatedBadge(result, compact, compactFontSize)
    }
  }
}

@Composable
private fun PopulatedBadge(
  result: ScoreResult,
  compact: Boolean,
  compactFontSize: TextUnit
) {
  val maybeCompactFontSize = if (compact) compactFontSize else TextUnit.Unspecified
  val iconRes = remember(result.rank) { rankIconRes(result.rank) }
  val image = iconRes?.let { painterResource(it) }
  val isMaxRank = result.rank >= 9
  val nextRank = if (!isMaxRank) rankFromScore(result.score) + 1 else null
  val nextIconRes = remember(nextRank) { nextRank?.let { rankIconRes(it) } }
  val nextImage = nextIconRes?.let { painterResource(it) }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    if (image != null) {
      androidx.compose.foundation.Image(
        painter = image,
        contentDescription = RANK_NAMES.getOrElse(result.rank - 1) { "" },
        modifier = Modifier.badgeIcon(compact),
      )
    }
  }

  Spacer(Modifier.height(1.dp))

  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = stringResource(R.string.rank_score_label),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontSize = maybeCompactFontSize,
      lineHeight = maybeCompactFontSize,
    )
    Spacer(Modifier.width(3.dp))
    RollingNumbers(
      text = result.score.toInt().toString(),
      textStyle = MaterialTheme.typography.titleSmall.copy(
        fontWeight = FontWeight.Bold,
        fontSize = maybeCompactFontSize,
        lineHeight = maybeCompactFontSize,
        color = MaterialTheme.colorScheme.onSurface,
      ),
    )
  }

  Spacer(Modifier.height(1.dp))

  if (!isMaxRank) {
    val currentFloor = RANK_THRESH.getOrNull(result.rank - 1) ?: return
    val nextThresh = RANK_THRESH.getOrNull(result.rank) ?: return
    val range = nextThresh - currentFloor
    val progress = if (range > 0.0) ((result.score - currentFloor) / range).coerceIn(0.0, 1.0).toFloat() else 0f

    LinearProgressIndicator(
      progress = { progress },
      modifier = Modifier.progressBar(compact),
      color = MaterialTheme.colorScheme.primary,
      trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )

    Spacer(Modifier.height(1.dp))

    val pointsNeeded = (nextThresh - result.score).toInt()
    Row(
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "$pointsNeeded pts to ",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = maybeCompactFontSize,
        lineHeight = maybeCompactFontSize,
      )
      if (nextImage != null) {
        Image(
          painter = nextImage,
          contentDescription = null,
          modifier = Modifier.size(if (compact) 18.dp else 22.dp),
        )
      }
    }
  }
}

@Composable
private fun LockedBadge(
  result: ScoreResult,
  compact: Boolean,
  compactFontSize: TextUnit
) {
  val maybeCompactFontSize = if (compact) compactFontSize else TextUnit.Unspecified
  val racesProgress = (result.totalVs / 100f).coerceIn(0f, 1f)

  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Image(
      painter = painterResource(R.drawable.ic_badge_locked),
      contentDescription = stringResource(R.string.rank_locked),
      modifier = Modifier.badgeIcon(compact),
    )

    Spacer(Modifier.height(1.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = stringResource(R.string.rank_score_label),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = maybeCompactFontSize,
        lineHeight = maybeCompactFontSize,
      )
      Spacer(Modifier.width(3.dp))
      RollingNumbers(
        text = wouldBeScore(result).toInt().toString(),
        textStyle = MaterialTheme.typography.titleSmall.copy(
          fontWeight = FontWeight.Bold,
          fontSize = maybeCompactFontSize,
          lineHeight = maybeCompactFontSize,
          color = MaterialTheme.colorScheme.onSurface,
        ),
      )
    }

    Spacer(Modifier.height(1.dp))

    LinearProgressIndicator(
      progress = { racesProgress },
      modifier = Modifier.progressBar(compact),
      color = MaterialTheme.colorScheme.primary,
      trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )

    Spacer(Modifier.height(1.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = stringResource(R.string.rank_races_format, result.totalVs),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = maybeCompactFontSize,
        lineHeight = maybeCompactFontSize,
      )
    }
  }
}
