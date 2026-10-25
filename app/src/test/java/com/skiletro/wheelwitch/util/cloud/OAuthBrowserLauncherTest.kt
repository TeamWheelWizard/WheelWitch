package com.skiletro.wheelwitch.util.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OAuthBrowserLauncherTest {
  @Test
  fun `opens OAuth URL in custom tab when available`() {
    val launches = mutableListOf<String>()
    val launcher =
        OAuthBrowserLauncher(
            openCustomTab = { url -> launches += "custom:$url" },
            openExternalBrowser = { url -> launches += "external:$url" },
            reportFailure = { failure -> throw AssertionError("unexpected launch failure", failure) },
        )

    launcher.open("https://www.dropbox.com/oauth2/authorize?client_id=test")

    assertThat(launches)
        .containsExactly("custom:https://www.dropbox.com/oauth2/authorize?client_id=test")
  }

  @Test
  fun `falls back to external browser when custom tab cannot launch`() {
    val launches = mutableListOf<String>()
    val failures = mutableListOf<Throwable>()
    val launcher =
        OAuthBrowserLauncher(
            openCustomTab = { throw IllegalStateException("no custom tabs") },
            openExternalBrowser = { url -> launches += "external:$url" },
            reportFailure = { failures += it },
        )

    launcher.open("https://www.dropbox.com/oauth2/authorize?client_id=test")

    assertThat(launches)
        .containsExactly("external:https://www.dropbox.com/oauth2/authorize?client_id=test")
    assertThat(failures).isEmpty()
  }

  @Test
  fun `reports external browser failure after custom tab failure`() {
    val customTabFailure = IllegalStateException("no custom tabs")
    val externalBrowserFailure = IllegalStateException("no browser")
    val failures = mutableListOf<Throwable>()
    val launcher =
        OAuthBrowserLauncher(
            openCustomTab = { throw customTabFailure },
            openExternalBrowser = { throw externalBrowserFailure },
            reportFailure = { failures += it },
        )

    launcher.open("https://www.dropbox.com/oauth2/authorize?client_id=test")

    assertThat(failures).containsExactly(externalBrowserFailure)
  }
}
