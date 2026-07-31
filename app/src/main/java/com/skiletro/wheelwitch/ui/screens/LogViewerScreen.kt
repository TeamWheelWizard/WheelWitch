package com.skiletro.wheelwitch.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.ui.components.ScreenHeader
import com.skiletro.wheelwitch.ui.theme.buttonShape
import com.skiletro.wheelwitch.ui.theme.surfaceShape
import com.skiletro.wheelwitch.util.launcher.BugReportLauncher
import com.skiletro.wheelwitch.util.log.LogTextRenderer
import com.skiletro.wheelwitch.util.log.LogViewerColors
import com.skiletro.wheelwitch.util.log.logViewerColorsFor
import com.skiletro.wheelwitch.viewmodel.LogViewerViewModel
import kotlinx.coroutines.launch

/**
 * Bug Report screen: read-only view of the in-memory + on-disk logs in
 * the same chronological order as the exported `.log` file, with copy
 * and multi-target share actions.
 *
 * The log text comes from [LogViewerViewModel] (rendered via
 * `LogExporter.exportToString`), so what the user reads is exactly what
 * the "share as file" option attaches. The screen reloads the snapshot
 * on every open via a [LaunchedEffect], so it reflects log entries
 * captured since the last visit.
 */
@Composable
fun LogViewerScreen(
  viewModel: LogViewerViewModel,
  onClose: () -> Unit,
) {
  val state by viewModel.state.collectAsState()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val copiedMessage = stringResource(R.string.log_viewer_copied_to_clipboard)
  val logColors = logViewerColors()
  val coloredLogText = remember(state.logText) { LogTextRenderer.colorizeLogReport(state.logText, logColors) }

  BackHandler(onBack = onClose)

  LaunchedEffect(Unit) { viewModel.reload() }

  LaunchedEffect(state.copyRequest) {
    if (state.copyRequest > 0L) {
      Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }
  }

  Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(modifier = Modifier.fillMaxSize()) {
      ScreenHeader(
        title = stringResource(R.string.log_viewer_title),
        onBack = onClose,
        trailing = {
          IconButton(onClick = { viewModel.copyToClipboard() }) {
            Icon(
              imageVector = ImageVector.vectorResource(R.drawable.ic_content_copy),
              contentDescription = stringResource(R.string.cd_copy),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
      )

      Box(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
      ) {
        if (state.isLoading) {
          CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
          SelectionContainer(modifier = Modifier.fillMaxSize()) {
            Text(
              text = coloredLogText,
              fontFamily = FontFamily.Monospace,
              fontSize = 13.sp,
              lineHeight = 18.sp,
              color = MaterialTheme.colorScheme.onSurface,
              modifier =
                Modifier.fillMaxSize()
                  .background(MaterialTheme.colorScheme.surfaceVariant, surfaceShape)
                  .verticalScroll(rememberScrollState())
                  .padding(12.dp),
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Spacer(modifier = Modifier.weight(1f))
        ShareOptionButton(
          icon = ImageVector.vectorResource(R.drawable.ic_share),
          label = stringResource(R.string.log_viewer_share_text),
          onClick = { BugReportLauncher.shareText(context, state.logText) },
        )
        Spacer(modifier = Modifier.width(8.dp))
        ShareOptionButton(
          icon = ImageVector.vectorResource(R.drawable.ic_attach_file),
          label = stringResource(R.string.log_viewer_share_file),
          onClick = { scope.launch { BugReportLauncher.launch(context) } },
        )
      }
    }
  }
}

/** Tonal share button with a leading icon, matching the settings Report button style. */
@Composable
private fun ShareOptionButton(
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Button(
    onClick = onClick,
    modifier = modifier,
    shape = buttonShape,
    contentPadding = ButtonDefaults.TextButtonContentPadding,
    colors =
      ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      ),
  ) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(modifier = Modifier.width(8.dp))
    Text(label)
  }
}

/**
 * Resolves the [LogViewerColors] used to render the report from the active
 * Material scheme. Plain/metadata pass through theme-adaptive roles; the
 * level colours come from [logViewerColorsFor], which picks light/dark-tuned
 * logcat hues (gray, blue, green, orange, red).
 */
@Composable
private fun logViewerColors(): LogViewerColors {
  val scheme = MaterialTheme.colorScheme
  return logViewerColorsFor(
    surface = scheme.surfaceVariant,
    onSurface = scheme.onSurface,
    onSurfaceVariant = scheme.onSurfaceVariant,
  )
}
