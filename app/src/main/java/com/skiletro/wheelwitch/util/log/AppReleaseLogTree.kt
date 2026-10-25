package com.skiletro.wheelwitch.util.log

import android.util.Log
import timber.log.Timber

/** Emits ERROR as logcat errors and all other priorities at WARN or above as warnings. */
open class AppReleaseLogTree : Timber.Tree() {
  override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

  override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
    if (priority == Log.ERROR) {
      Log.e(tag, message, t)
    } else {
      Log.w(tag, message, t)
    }
  }
}
