package com.skiletro.wheelwitch.util.cloud

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test

class SaveContentHashTest {
  private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
      for ((name, bytes) in entries) {
        z.putNextEntry(ZipEntry(name))
        z.write(bytes)
        z.closeEntry()
      }
    }
    return out.toByteArray()
  }

  @Test
  fun `same content different order same hash`() {
    val a = zip("RetroWFC/RMCP/rksys.dat" to byteArrayOf(1), "Wii/x.dat" to byteArrayOf(2))
    val b = zip("Wii/x.dat" to byteArrayOf(2), "RetroWFC/RMCP/rksys.dat" to byteArrayOf(1))
    assertThat(SaveContentHash.canonicalHash(a)).isEqualTo(SaveContentHash.canonicalHash(b))
  }

  @Test
  fun `manifest excluded`() {
    val a = zip("manifest.json" to byteArrayOf(9), "data.dat" to byteArrayOf(1))
    val b = zip("manifest.json" to byteArrayOf(8), "data.dat" to byteArrayOf(1))
    assertThat(SaveContentHash.canonicalHash(a)).isEqualTo(SaveContentHash.canonicalHash(b))
  }

  @Test
  fun `different content different hash`() {
    val a = zip("data.dat" to byteArrayOf(1))
    val b = zip("data.dat" to byteArrayOf(2))
    assertThat(SaveContentHash.canonicalHash(a)).isNotEqualTo(SaveContentHash.canonicalHash(b))
  }

  @Test
  fun `hash is 64 hex chars`() {
    val h = SaveContentHash.canonicalHash(zip("data.dat" to byteArrayOf(1)))
    assertThat(h).matches("[0-9a-f]{64}")
  }

  @Test
  fun `directory entries ignored`() {
    val out = java.io.ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
      z.putNextEntry(ZipEntry("dir/"))
      z.closeEntry()
      z.putNextEntry(ZipEntry("dir/a.dat"))
      z.write(byteArrayOf(1))
      z.closeEntry()
    }
    val other = zip("dir/a.dat" to byteArrayOf(1))
    assertThat(SaveContentHash.canonicalHash(out.toByteArray()))
        .isEqualTo(SaveContentHash.canonicalHash(other))
  }
}
