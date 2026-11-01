package com.skiletro.wheelwitch.ui.screens

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OnboardingScreenTest {

  @Test
  fun `runRomValidation returns header read failure`() = runTest {
    val result = runRomValidation<Nothing> { throw IOException("header unreadable") }

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
  }

  @Test
  fun `runRomValidation rethrows cancellation`() = runTest {
    var cancellation: CancellationException? = null

    try {
      runRomValidation<Unit> { throw CancellationException("picker cancelled") }
    } catch (e: CancellationException) {
      cancellation = e
    }

    assertThat(cancellation).isNotNull()
    assertThat(cancellation).hasMessageThat().isEqualTo("picker cancelled")
  }
}
