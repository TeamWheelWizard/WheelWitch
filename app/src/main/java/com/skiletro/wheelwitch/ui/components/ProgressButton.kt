package com.skiletro.wheelwitch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.ui.theme.WheelWitchPreviewTheme
import com.skiletro.wheelwitch.util.formatBytesPerSecond
import com.skiletro.wheelwitch.util.formatDownloadProgress

/**
 * Determinate progress with optional byte counts, bytes-per-second rate, and file counts.
 * [currentFile] replaces [label] beneath the bar when supplied.
 */
@Composable
fun ProgressButton(
  progress: Float,
  label: String,
  bytesPerSecond: Long? = null,
  bytesDownloaded: Long = 0L,
  totalBytes: Long = 0L,
  filesDone: Int = 0,
  filesTotal: Int = 0,
  currentFile: String? = null,
) {
  val percent = (progress.coerceIn(0f, 1f) * 100f).toInt()
  val showSize = bytesPerSecond != null && (bytesDownloaded > 0L || totalBytes > 0L)
  val sizeText = if (showSize) formatDownloadProgress(bytesDownloaded, totalBytes) else ""
  val showFiles = filesTotal > 0
  Column(modifier = Modifier.widthIn(min = 220.dp), horizontalAlignment = Alignment.End) {
    LinearProgressIndicator(
      progress = { progress.coerceIn(0f, 1f) },
      modifier =
        Modifier.fillMaxWidth()
          .height(6.dp)
          .clip(RoundedCornerShape(3.dp)),
      color = MaterialTheme.colorScheme.primary,
      trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = stringResource(R.string.download_percent_format, percent),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
      )
      if (showFiles) {
        Spacer(modifier = Modifier.weight(1f))
        Text(
          text = stringResource(R.string.status_extracting_files_format, filesDone, filesTotal),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.End,
          fontWeight = FontWeight.Medium,
        )
      }
      if (showSize) {
        Spacer(modifier = Modifier.weight(1f))
        Text(
          text = sizeText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          fontWeight = FontWeight.Medium,
        )
      }
      if (bytesPerSecond != null) {
        Spacer(modifier = Modifier.weight(1f))
        Text(
          text = formatBytesPerSecond(bytesPerSecond),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.End,
          fontWeight = FontWeight.Medium,
        )
      }
    }
    Text(
      text = currentFile ?: label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.End,
      fontWeight = if (currentFile != null) FontWeight.Normal else FontWeight.Medium,
      fontFamily = if (currentFile != null) FontFamily.Monospace else null,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun ProgressButtonPreview() {
  WheelWitchPreviewTheme {
    ProgressButton(
      progress = 0.67f,
      label = "Downloading…",
      bytesPerSecond = 1_234_567L,
      bytesDownloaded = 67_000_000L,
      totalBytes = 100_000_000L,
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun ProgressButtonExtractingPreview() {
  WheelWitchPreviewTheme {
    ProgressButton(
      progress = 0.47f,
      label = "Extracting files...",
      filesDone = 482,
      filesTotal = 1024,
      currentFile = "riivolution/USA/Music/course_47.brstm",
    )
  }
}
