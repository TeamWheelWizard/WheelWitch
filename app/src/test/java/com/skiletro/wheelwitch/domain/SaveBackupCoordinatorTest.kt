package com.skiletro.wheelwitch.domain

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.data.DolphinTree
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SaveBackupCoordinatorTest {

  private val context: Context = mockk(relaxed = true)
  private val tree: DolphinTree = mockk(relaxed = true)

  @Test
  fun `run reports success and fires onSuccess when the op succeeds`() = runTest {
    val coordinator = SaveBackupCoordinator({ tree })
    var onSuccess = 0

    val outcome =
      coordinator.run(context, "backup", onSuccess = { onSuccess++ }) {
        assertThat(it).isSameInstanceAs(tree)
        Result.success(Unit)
      }

    assertThat(outcome).isEqualTo(SaveOpOutcome.Success)
    assertThat(onSuccess).isEqualTo(1)
  }

  @Test
  fun `run reports the exception message when the op fails`() = runTest {
    val coordinator = SaveBackupCoordinator({ tree })

    val outcome =
      coordinator.run(context, "backup", fallbackError = { "write failed" }) {
        Result.failure<Unit>(RuntimeException("disk full"))
      }

    assertThat(outcome).isEqualTo(SaveOpOutcome.Failure("disk full"))
  }

  @Test
  fun `run reports the fallback error when the failure has no message`() = runTest {
    val coordinator = SaveBackupCoordinator({ tree })

    val outcome =
      coordinator.run(context, "backup", fallbackError = { "write failed" }) {
        Result.failure<Unit>(RuntimeException())
      }

    assertThat(outcome).isEqualTo(SaveOpOutcome.Failure("write failed"))
  }

  @Test
  fun `run reports not configured without invoking the op when the tree is null`() = runTest {
    val coordinator = SaveBackupCoordinator({ null })
    var opRuns = 0

    val outcome =
      coordinator.run(context, "backup", notConfiguredError = { "no storage" }) {
        opRuns++
        Result.success(Unit)
      }

    assertThat(outcome).isEqualTo(SaveOpOutcome.Failure("no storage"))
    assertThat(opRuns).isEqualTo(0)
  }

  @Test
  fun `run does not fire onSuccess when the tree is null`() = runTest {
    val coordinator = SaveBackupCoordinator({ null })
    var onSuccess = 0

    coordinator.run(context, "backup", notConfiguredError = { "no storage" }, onSuccess = { onSuccess++ }) {
      Result.success(Unit)
    }

    assertThat(onSuccess).isEqualTo(0)
  }
}