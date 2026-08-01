package com.skiletro.wheelwitch.util.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.skiletro.wheelwitch.BuildConfig
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.util.log.LogExporter
import java.io.File

/**
 * Builds and launches the bug-report share intent, and provides a
 * clipboard fallback for devices without a compatible share target.
 *
 * The share intent writes a fresh log file to the app cache, exposes it
 * via [FileProvider], and opens the system chooser pre-filled with the
 * developer's bug-report email address (from [BuildConfig.LOGS_EMAIL]).
 */
object BugReportLauncher {
  /**
   * Flushes the log buffer, copies a summary to the clipboard as a
   * fallback, and opens the system share chooser pre-filled with the
   * bug-report email and the log file as an attachment. Returns true
   * when the chooser was launched.
   */
  fun launch(context: Context): Boolean {
    val logFile = LogExporter.flushToCacheFile(context)
    val summary = buildSummary(context)
    val send = buildSendIntent(context, logFile, summary)
    val chooser = Intent.createChooser(send, context.getString(R.string.bug_report_chooser_title))
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(chooser); true }
      .getOrElse {
        copyToClipboard(context, summary)
        Toast.makeText(context, R.string.bug_report_share_failed, Toast.LENGTH_LONG).show()
        false
      }
  }

  /**
   * Shares [text] via the system chooser without an attachment. Used by
   * the Log Viewer "share as text" option (e.g. Discord, email). Falls
   * back to [copyToClipboard] + a toast when no share target exists.
   * Returns true when the chooser was launched.
   */
  fun shareText(context: Context, text: String): Boolean {
    val send = buildTextIntent(context, text)
    val chooser = Intent.createChooser(send, context.getString(R.string.bug_report_chooser_title))
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(chooser); true }
      .getOrElse {
        copyToClipboard(context, text)
        Toast.makeText(context, R.string.bug_report_share_failed, Toast.LENGTH_LONG).show()
        false
      }
  }

  /**
   * Copies [text] to the system clipboard under a [ClipData] labeled
   * with the app name, so paste targets can identify its source.
   */
  fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
  }

  private fun buildSendIntent(context: Context, logFile: File, summary: String): Intent {
    val uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      logFile,
    )
    val subject = context.getString(
      R.string.bug_report_subject,
      BuildConfig.VERSION_NAME,
      BuildConfig.GIT_HASH,
    )
    val body = context.getString(R.string.bug_report_body_format, summary)
    return Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.LOGS_EMAIL))
      putExtra(Intent.EXTRA_SUBJECT, subject)
      putExtra(Intent.EXTRA_TEXT, body)
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  private fun buildTextIntent(context: Context, text: String): Intent {
    val subject = context.getString(
      R.string.bug_report_subject,
      BuildConfig.VERSION_NAME,
      BuildConfig.GIT_HASH,
    )
    return Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.LOGS_EMAIL))
      putExtra(Intent.EXTRA_SUBJECT, subject)
      putExtra(Intent.EXTRA_TEXT, text)
    }
  }

  private fun buildSummary(context: Context): String {
    val errorSummary = LogExporter.lastErrorSummary()
    return if (errorSummary != null) {
      context.getString(R.string.bug_report_summary_with_error, errorSummary)
    } else {
      context.getString(R.string.bug_report_summary_no_error)
    }
  }
}
