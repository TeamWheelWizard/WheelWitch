package com.skiletro.wheelwitch.util.cloud

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import timber.log.Timber

/** Opens OAuth pages in a Custom Tab, with a normal browser as a resilient fallback. */
internal class OAuthBrowserLauncher(
    private val openCustomTab: (String) -> Unit,
    private val openExternalBrowser: (String) -> Unit,
    private val reportFailure: (Throwable) -> Unit = { error ->
      Timber.tag(TAG).w(error, "no browser available for OAuth")
    },
) {
  fun open(url: String) {
    runCatching { openCustomTab(url) }
        .recoverCatching { openExternalBrowser(url) }
        .onFailure(reportFailure)
  }

  companion object {
    fun create(context: Context): OAuthBrowserLauncher =
        OAuthBrowserLauncher(
            openCustomTab = { url -> launchCustomTab(context, url) },
            openExternalBrowser = { url -> launchExternalBrowser(context, url) },
        )

    private fun launchCustomTab(context: Context, url: String) {
      val provider =
          CustomTabsClient.getPackageName(context, null)
              ?: throw ActivityNotFoundException("No Custom Tabs browser")
      CustomTabsIntent.Builder()
          .setShowTitle(true)
          .build()
          .also { intent ->
            intent.intent.setPackage(provider)
            intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          .launchUrl(context, Uri.parse(url))
    }

    private fun launchExternalBrowser(context: Context, url: String) {
      context.startActivity(
          Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    }

    private const val TAG = "OAuthBrowserLauncher"
  }
}
