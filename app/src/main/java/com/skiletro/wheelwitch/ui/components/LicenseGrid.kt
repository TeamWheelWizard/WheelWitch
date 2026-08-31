package com.skiletro.wheelwitch.ui.components

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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dontsaybojio.rollingnumbers.RollingNumbers
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.model.LicenseInfo
import com.skiletro.wheelwitch.model.ScoreResult
import com.skiletro.wheelwitch.ui.theme.CtmkfFontFamily
import com.skiletro.wheelwitch.ui.theme.WheelWitchPreviewTheme
import com.skiletro.wheelwitch.ui.theme.surfaceShape
import kotlin.math.round

/** Grid width below which licenses render as a single scrollable column. */
private val TwoColumnMinWidth = 560.dp

/** Cell width below which the cell switches to compact chrome and tighter spacing. */
private val CompactCellWidthThreshold = 360.dp

/** Cell height below which the cell switches to compact chrome and tighter spacing. */
private val CompactCellHeightThreshold = 140.dp

@Composable
fun LicenseGrid(
  licenses: List<LicenseInfo>,
  scoreResults: Map<Int, ScoreResult?>,
  isLoading: Boolean,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 20.dp, vertical = 8.dp),
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      if (maxWidth >= TwoColumnMinWidth) {
        Column(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          licenses.chunked(2).forEach { pair ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              pair.getOrNull(0)?.let { first ->
                LicenseCell(
                  license = first,
                  scoreResult = scoreResults[first.slotIndex],
                  modifier = Modifier.weight(1f),
                )
              }
              pair.getOrNull(1)?.let { second ->
                LicenseCell(
                  license = second,
                  scoreResult = scoreResults[second.slotIndex],
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
        PopulatedCell(license = populated, scoreResult = scoreResult)
      } else {
        EmptyCell()
      }
    }
  }
}

@Composable
fun PopulatedCell(license: LicenseInfo, scoreResult: ScoreResult?) {
  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val compact = maxWidth < CompactCellWidthThreshold || maxHeight < CompactCellHeightThreshold
    val compactFontSize = 9.sp
    val maybeCompactFontSize = if (compact) compactFontSize else TextUnit.Unspecified
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
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = maybeCompactFontSize,
            lineHeight = maybeCompactFontSize,
          )
        }
        Spacer(modifier = Modifier.height(3.dp))
        val vr = license.ratingVr ?: license.vr ?: 0
        val wins = license.raceWins ?: 0
        val losses = license.raceLosses ?: 0
        val total = wins + losses
        val winRate = if (total == 0) {
          0
        } else {
          round((wins / total.toDouble()).coerceIn(0.0, 1.0) * 1000) / 10.0
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          RollingNumbers(
            text = "$vr VR",
            textStyle =
              MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = maybeCompactFontSize,
                lineHeight = maybeCompactFontSize,
              ),
          )
        }
        Spacer(modifier = Modifier.height(1.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          RollingNumbers(
            text = "${winRate}% WR",
            textStyle =
              MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                fontSize = maybeCompactFontSize,
                lineHeight = maybeCompactFontSize,
              ),
          )
        }
      }
      Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))
      RankBadge(
        result = scoreResult,
        compact = compact,
        compactFontSize = compactFontSize
      )
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
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 32.dp, vertical = 24.dp),
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

private const val PreviewMiiBase64 = "AAAAbgBvACAAbgBhAG0AZQAAAAAAAEBAAAAAAAAAAAAEBEJAMYAoogiMCFgUSriNAIoAiiUEAAAAAAAAAAAAAAAAAAAAAAAAAAA="

private val PreviewLicenses =
  listOf(
    LicenseInfo(
      slotIndex = 0,
      exists = true,
      miiDataBase64 = PreviewMiiBase64,
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
      miiDataBase64 = PreviewMiiBase64,
      miiName = "Skiletro",
      friendCode = "9876-5432-1098",
      vr = 7200,
      raceWins = 250,
      raceLosses = 45,
      ratingVr = 999999,
    ),
    LicenseInfo(slotIndex = 2, exists = false),
    LicenseInfo(
      slotIndex = 3,
      exists = true,
      miiDataBase64 = PreviewMiiBase64,
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
    1 to ScoreResult(score = 91.2, rank = 7, vrNorm = 83.0, winPct = 84.0, firstsNorm = 60.0,
        distNorm = 50.0, dist1stNorm = 40.0, totalVs = 295, meetsRaceReq = true),
    3 to ScoreResult(score = 0.0, rank = 0, vrNorm = 31.0, winPct = 36.0, firstsNorm = 20.0,
        distNorm = 10.0, dist1stNorm = 5.0, totalVs = 100, meetsRaceReq = false),
  )

@Preview(name = "LicenseGrid 1000dp", widthDp = 1000, heightDp = 225, fontScale = 1.3f, showBackground = true)
@Composable
private fun LicenseGridCompactTwoColumnPreview() {
  WheelWitchPreviewTheme {
    LicenseGrid(
      licenses = PreviewLicenses,
      scoreResults = PreviewScores,
      isLoading = false,
    )
  }
}

@Preview(name = "LicenseGrid 1000dp", widthDp = 1000, heightDp = 310, fontScale = 1.3f, showBackground = true)
@Composable
private fun LicenseGridRegularTwoColumnPreview() {
  WheelWitchPreviewTheme {
    LicenseGrid(
      licenses = PreviewLicenses,
      scoreResults = PreviewScores,
      isLoading = false,
    )
  }
}

@Preview(name = "LicenseGrid 320dp", widthDp = 320, heightDp = 320, fontScale = 1.3f, showBackground = true)
@Composable
private fun LicenseGridNarrowPreview() {
  WheelWitchPreviewTheme {
    LicenseGrid(
      licenses = PreviewLicenses,
      scoreResults = PreviewScores,
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
      isLoading = false,
    )
  }
}
