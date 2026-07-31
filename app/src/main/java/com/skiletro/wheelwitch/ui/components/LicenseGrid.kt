package com.skiletro.wheelwitch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dontsaybojio.rollingnumbers.RollingNumbers
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.LicenseInfo
import com.skiletro.wheelwitch.model.ScoreResult
import com.skiletro.wheelwitch.model.VanityBadge
import com.skiletro.wheelwitch.ui.theme.CtmkfFontFamily
import com.skiletro.wheelwitch.ui.theme.WheelWitchPreviewTheme
import com.skiletro.wheelwitch.ui.theme.surfaceShape

/** Grid width below which licenses render as a single scrollable column. */
private val TwoColumnMinWidth = 560.dp

/** Cell width below which the cell switches to compact chrome and tighter spacing. */
private val CompactCellThreshold = 360.dp

@Composable
fun LicenseGrid(
  licenses: List<LicenseInfo>,
  scoreResults: Map<Int, ScoreResult?>,
  badges: Map<String, VanityBadge>,
  isLoading: Boolean,
) {
  Box(
    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      if (maxWidth >= TwoColumnMinWidth) {
        Column(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          licenses.chunked(2).forEach { pair ->
            Row(
              modifier = Modifier.fillMaxWidth().weight(1f),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              pair.getOrNull(0)?.let { first ->
                LicenseCell(
                  license = first,
                  scoreResult = scoreResults[first.slotIndex],
                  badge = first.friendCode?.let { badges[it] },
                  modifier = Modifier.weight(1f),
                )
              }
              pair.getOrNull(1)?.let { second ->
                LicenseCell(
                  license = second,
                  scoreResult = scoreResults[second.slotIndex],
                  badge = second.friendCode?.let { badges[it] },
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(licenses, key = { it.slotIndex }) { license ->
            LicenseCell(
              license = license,
              scoreResult = scoreResults[license.slotIndex],
              badge = license.friendCode?.let { badges[it] },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    }
    if (isLoading) {
      CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }
  }
}

@Composable
fun LicenseCell(
  license: LicenseInfo?,
  scoreResult: ScoreResult?,
  badge: VanityBadge? = null,
  modifier: Modifier = Modifier,
) {
  val exists = license?.exists == true
  val background =
    if (exists) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

  Surface(
    modifier = modifier,
    shape = surfaceShape,
    color = background,
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      val populated = license?.takeIf { it.exists }
      if (populated != null) {
        PopulatedCell(license = populated, scoreResult = scoreResult, badge = badge)
      } else {
        EmptyCell()
      }
    }
  }
}

@Composable
fun PopulatedCell(license: LicenseInfo, scoreResult: ScoreResult?, badge: VanityBadge? = null) {
  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val compact = maxWidth < CompactCellThreshold
    Row(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(
            horizontal = if (compact) 12.dp else 14.dp,
            vertical = if (compact) 10.dp else 14.dp,
          ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      MiiFace(
        imageBase64 = null,
        miiDataBase64 = license.miiDataBase64,
        modifier = Modifier.size(if (compact) 56.dp else 84.dp),
      )
      Spacer(modifier = Modifier.width(if (compact) 10.dp else 14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = license.miiName ?: stringResource(R.string.save_info_no_name),
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.titleLarge,
          fontFamily = CtmkfFontFamily,
          color = MaterialTheme.colorScheme.onSurface,
        )
        license.friendCode?.let { fc ->
          Text(
            text = fc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.height(3.dp))
        val vr = license.ratingVr ?: license.vr ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "VR ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          RollingNumbers(
            text = vr.toString(),
            textStyle =
              MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
              ),
          )
        }
        Spacer(modifier = Modifier.height(1.dp))
        val wins = license.raceWins ?: 0
        val losses = license.raceLosses ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "W ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          RollingNumbers(
            text = wins.toString(),
            textStyle =
              MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
          )
          Text(
            text = " / L ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          RollingNumbers(
            text = losses.toString(),
            textStyle =
              MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
          )
        }
      }
      Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))
      RankBadge(result = scoreResult, vanityBadge = badge, compact = compact)
    }
  }
}

@Composable
fun EmptyCell() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(
      text = stringResource(R.string.save_info_empty_slot),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    )
  }
}

@Composable
fun EmptySaveBody(isLoading: Boolean) {
  Box(
    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
    contentAlignment = Alignment.Center,
  ) {
    if (isLoading) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
          text = stringResource(R.string.save_info_loading),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    } else {
      Text(
        text = stringResource(R.string.save_info_no_licenses),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

private val PreviewLicenses =
  listOf(
    LicenseInfo(
      slotIndex = 0,
      exists = true,
      miiName = "Bomber",
      friendCode = "1234-5678-9012",
      vr = 4800,
      raceWins = 120,
      raceLosses = 60,
      ratingVr = 6100,
    ),
    LicenseInfo(
      slotIndex = 1,
      exists = true,
      miiName = "Skiletro",
      friendCode = "9876-5432-1098",
      vr = 7200,
      raceWins = 250,
      raceLosses = 45,
      ratingVr = 8300,
    ),
    LicenseInfo(slotIndex = 2, exists = false),
    LicenseInfo(
      slotIndex = 3,
      exists = true,
      miiName = "Turbo",
      friendCode = "1357-2468-0246",
      vr = 2200,
      raceWins = 40,
      raceLosses = 70,
      ratingVr = 3100,
    ),
  )

private val PreviewScores =
  mapOf(
    0 to ScoreResult(score = 76.4, rank = 6, vrNorm = 70.0, winPct = 66.0, firstsNorm = 50.0,
        distNorm = 40.0, dist1stNorm = 30.0, totalVs = 180, meetsRaceReq = true),
    1 to ScoreResult(score = 91.2, rank = 8, vrNorm = 83.0, winPct = 84.0, firstsNorm = 60.0,
        distNorm = 50.0, dist1stNorm = 40.0, totalVs = 295, meetsRaceReq = true),
    3 to ScoreResult(score = 0.0, rank = 0, vrNorm = 31.0, winPct = 36.0, firstsNorm = 20.0,
        distNorm = 10.0, dist1stNorm = 5.0, totalVs = 110, meetsRaceReq = false),
  )

@Preview(name = "LicenseGrid 320dp", widthDp = 320, heightDp = 320, fontScale = 1.3f, showBackground = true)
@Composable
private fun LicenseGridNarrowPreview() {
  WheelWitchPreviewTheme {
    LicenseGrid(
      licenses = PreviewLicenses,
      scoreResults = PreviewScores,
      badges = emptyMap(),
      isLoading = false,
    )
  }
}

@Preview(name = "LicenseGrid 360dp", widthDp = 360, heightDp = 320, fontScale = 1.3f, showBackground = true)
@Composable
private fun LicenseGridMediumPreview() {
  WheelWitchPreviewTheme {
    LicenseGrid(
      licenses = PreviewLicenses,
      scoreResults = PreviewScores,
      badges = emptyMap(),
      isLoading = false,
    )
  }
}

@Preview(name = "LicenseGrid 412dp", widthDp = 412, heightDp = 320, fontScale = 1.3f, showBackground = true)
@Composable
private fun LicenseGridWidePreview() {
  WheelWitchPreviewTheme {
    LicenseGrid(
      licenses = PreviewLicenses,
      scoreResults = PreviewScores,
      badges = emptyMap(),
      isLoading = false,
    )
  }
}
