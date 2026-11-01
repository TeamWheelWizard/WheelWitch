package com.skiletro.wheelwitch.domain

import android.content.Context
import com.skiletro.wheelwitch.data.DolphinTree
import timber.log.Timber

/** Outcome of one unified save operation (backup/restore/delete). */
sealed interface SaveOpOutcome {
  data object Success : SaveOpOutcome
  data class Failure(val message: String, val throwable: Throwable? = null) : SaveOpOutcome
}

/**
 * Runs the unified save operations (full or RR-only backup, restore,
 * and delete) against the persisted [DolphinTree]. Resolves the tree
 * once, normalises failures into a user-facing message, and reports
 * the outcome so the calling ViewModel can update timestamps and
 * re-read state. [SaveManager] itself remains in the data layer; the
 * ViewModel passes its operations in as [op].
 */
class SaveBackupCoordinator(
  private val treeFactory: (Context) -> DolphinTree?,
) {
  /**
   * Resolves the tree and runs [op] against it, mapping the result to
   * a [SaveOpOutcome]. When no tree is persisted, reports [notConfiguredError]
   * without running [op]. On failure, reports the exception message or,
   * when null, [fallbackError]. On success, fires [onSuccess] first.
   */
  suspend fun run(
    context: Context,
    logTag: String,
    notConfiguredError: () -> String = { "" },
    fallbackError: () -> String = { "" },
    onSuccess: () -> Unit = {},
    op: suspend (DolphinTree) -> Result<*>,
  ): SaveOpOutcome {
    val tree = treeFactory(context)
    if (tree == null) return SaveOpOutcome.Failure(notConfiguredError())
    val failure = op(tree).exceptionOrNull()
    return if (failure != null) {
      Timber.tag(TAG).e(failure, "$logTag failed")
      SaveOpOutcome.Failure(failure.message ?: fallbackError(), failure)
    } else {
      onSuccess()
      SaveOpOutcome.Success
    }
  }

  companion object {
    private const val TAG = "SaveBackupOp"
  }
}