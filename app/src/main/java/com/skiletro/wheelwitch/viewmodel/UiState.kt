package com.skiletro.wheelwitch.viewmodel

import androidx.compose.runtime.Immutable
import com.skiletro.wheelwitch.data.ExtractingPhase
import com.skiletro.wheelwitch.model.PackStatus
import com.skiletro.wheelwitch.util.io.DownloadProgress

/** Pack status checks and install progress; successful installs trigger a fresh status check. */
@Immutable
sealed class UiState {
  /** Initial state before the first check completes. */
  data object Idle : UiState()

  /**
   * Keeps the last resolved status visible during a check. Installation is disabled, but an
   * installed pack can still be launched. [previousStatus] is null when no ready status is available.
   */
  data class Checking(val previousStatus: PackStatus?) : UiState()

  /** A status check finished; [status] is the current pack state. */
  data class Ready(val status: PackStatus) : UiState()

  /** Download and extraction phases share the UI's busy-state guard. */
  @Immutable
  sealed class Installing : UiState() {
    /** Zip is being fetched from the update server. */
    data class Downloading(val progress: DownloadProgress) : Installing()

    /**
     * Zip is being unpacked into the SAF tree. [phase] tells the UI
     * whether the directory pre-pass is running or the per-file
     * writes are live; [currentFile] is the entry currently being
     * written, or null during the pre-pass and between files.
     */
    data class Extracting(
      val phase: ExtractingPhase,
      val filesDone: Int,
      val filesTotal: Int,
      val currentFile: String?,
      val bytesDone: Long,
      val bytesTotal: Long,
    ) : Installing()
  }

  /** Install completion, immediately followed by a new status check. */
  data object Installed : UiState()

  /** A status check or install failed; [message] is user-facing. */
  data class Error(val message: String) : UiState()
}
