package com.skiletro.wheelwitch.util.io

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ZipSafetyTest {

  @Test
  fun `isSafeEntryName accepts simple filename`() {
    assertThat(ZipSafety.isSafeEntryName("file.txt")).isTrue()
  }

  @Test
  fun `isSafeEntryName accepts nested relative path`() {
    assertThat(ZipSafety.isSafeEntryName("a/b/file.txt")).isTrue()
  }

  @Test
  fun `isSafeEntryName accepts dot-slash prefix`() {
    assertThat(ZipSafety.isSafeEntryName("./a/b/file.txt")).isTrue()
  }

  @Test
  fun `isSafeEntryName accepts bare dot-slash filename`() {
    assertThat(ZipSafety.isSafeEntryName("./sneaky.txt")).isTrue()
  }

  @Test
  fun `isSafeEntryName rejects absolute path`() {
    assertThat(ZipSafety.isSafeEntryName("/etc/passwd")).isFalse()
  }

  @Test
  fun `isSafeEntryName rejects parent directory component`() {
    assertThat(ZipSafety.isSafeEntryName("../../escape.txt")).isFalse()
  }

  @Test
  fun `isSafeEntryName rejects parent directory in middle`() {
    assertThat(ZipSafety.isSafeEntryName("pack/../../escape.txt")).isFalse()
  }

  @Test
  fun `isSafeEntryName rejects current directory in middle`() {
    assertThat(ZipSafety.isSafeEntryName("foo/./bar.txt")).isFalse()
  }

  @Test
  fun `isSafeEntryName rejects absolute path after dot-slash`() {
    assertThat(ZipSafety.isSafeEntryName("./../../escape.txt")).isFalse()
  }
}
