package com.skiletro.wheelwitch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.skiletro.wheelwitch.ui.screens.MainScreen
import com.skiletro.wheelwitch.ui.theme.ThemeController
import com.skiletro.wheelwitch.ui.theme.WheelWitchTheme
import com.skiletro.wheelwitch.util.cloud.DropboxRedirect

/**
 * Single-activity entry point. Hosts [MainScreen] which orchestrates
 * the onboarding wizard and the home/settings overlay stack.
 */
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    DropboxRedirect.offer(intent?.data)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
    setContent {
      val theme = ThemeController.remember(this@MainActivity)
      WheelWitchTheme(themeMode = theme.themeMode, appTheme = theme.appTheme) {
        MainScreen(
          appTheme = theme.appTheme,
          onChangeAppTheme = theme::setAppTheme,
          themeMode = theme.themeMode,
          onChangeThemeMode = theme::setThemeMode,
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    DropboxRedirect.offer(intent.data)
  }
}
