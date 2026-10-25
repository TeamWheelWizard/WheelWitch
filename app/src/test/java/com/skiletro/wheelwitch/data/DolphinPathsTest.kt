package com.skiletro.wheelwitch.data

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.util.launcher.DolphinLauncher
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DolphinPathsTest {

  @Test
  fun `physicalRoot swaps release package to dolphin package`() {
    val ctx =
      mockWheelWitchContext(
        packageName = "com.skiletro.wheelwitch",
        externalFilesDir =
          File("/storage/emulated/0/Android/data/com.skiletro.wheelwitch/files"),
      )

    val root = DolphinPaths.physicalRoot(ctx)

    assertThat(root)
      .isEqualTo("/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files")
  }

  @Test
  fun `physicalRoot swaps debug package to dolphin package`() {
    val ctx =
      mockWheelWitchContext(
        packageName = "com.skiletro.wheelwitch.debug",
        externalFilesDir =
          File("/storage/emulated/0/Android/data/com.skiletro.wheelwitch.debug/files"),
      )

    val root = DolphinPaths.physicalRoot(ctx)

    assertThat(root)
      .isEqualTo("/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files")
  }

  @Test
  fun `physicalRoot lives under Android data and ends in files`() {
    val ctx =
      mockWheelWitchContext(
        externalFilesDir = File("/storage/emulated/10/Android/data/com.skiletro.wheelwitch/files"),
      )

    val root = DolphinPaths.physicalRoot(ctx)

    assertThat(root).contains("Android/data/")
    assertThat(root).endsWith("/files")
    assertThat(root).startsWith("/storage/emulated/")
  }

  @Test
  fun `physicalRoot only replaces the first occurrence of the package name`() {
    // Preserve package-name occurrences in app-owned subdirectories.
    val ctx =
      mockWheelWitchContext(
        externalFilesDir =
          File(
            "/storage/emulated/0/Android/data/com.skiletro.wheelwitch/files/" +
              "com.skiletro.wheelwitch/cache"
          )
      )

    val root = DolphinPaths.physicalRoot(ctx)

    assertThat(root)
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/" +
          "com.skiletro.wheelwitch/cache"
      )
  }

  @Test
  fun `physicalRoot throws when getExternalFilesDir returns null`() {
    val ctx = mockk<Context>(relaxed = true)
    every { ctx.getExternalFilesDir(null) } returns null

    val ex = assertThrows<IllegalStateException> { DolphinPaths.physicalRoot(ctx) }

    assertThat(ex.message).contains("External files dir unavailable")
  }

  @Test
  fun `wheelWitchDir appends the WheelWitch subpath to the physical root`() {
    val ctx =
      mockWheelWitchContext(
        externalFilesDir = File("/storage/emulated/0/Android/data/com.skiletro.wheelwitch/files")
      )

    val dir = DolphinPaths.wheelWitchDir(ctx)

    assertThat(dir.absolutePath)
      .isEqualTo("/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch")
  }

  @Test
  fun `packDir sits under wheelWitchDir`() {
    val ctx = mockWheelWitchContext()

    val dir = DolphinPaths.packDir(ctx)

    assertThat(dir.absolutePath)
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch/pack"
      )
  }

  @Test
  fun `romDir sits under wheelWitchDir`() {
    val ctx = mockWheelWitchContext()

    val dir = DolphinPaths.romDir(ctx)

    assertThat(dir.absolutePath)
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch/rom"
      )
  }

  @Test
  fun `rrJsonFile lives in the rom dir with the rr_autostartfile json filename`() {
    val ctx = mockWheelWitchContext()

    val file = DolphinPaths.rrJsonFile(ctx)

    assertThat(file.name).isEqualTo("rr_autostartfile.json")
    assertThat(file.parentFile?.absolutePath)
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch/rom"
      )
    assertThat(file.absolutePath).doesNotContain("pack/")
  }

  @Test
  fun `configIni is at the physical root Config Dolphin ini`() {
    val ctx = mockWheelWitchContext()

    val file = DolphinPaths.configIni(ctx)

    assertThat(file.absolutePath)
      .isEqualTo("/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Config/Dolphin.ini")
  }

  @Test
  fun `wiiDir sits at the physical root`() {
    val ctx = mockWheelWitchContext()

    val dir = DolphinPaths.wiiDir(ctx)

    assertThat(dir.absolutePath)
      .isEqualTo("/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Wii")
  }

  @Test
  fun `shared2Dir sits under wiiDir`() {
    val ctx = mockWheelWitchContext()

    val dir = DolphinPaths.shared2Dir(ctx)

    assertThat(dir.absolutePath)
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Wii/shared2"
      )
  }

  @Test
  fun `faceLibDir sits under shared2 menu FaceLib`() {
    val ctx = mockWheelWitchContext()

    val dir = DolphinPaths.faceLibDir(ctx)

    assertThat(dir.absolutePath)
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Wii/shared2/menu/FaceLib"
      )
  }

  @Test
  fun `pulsarRrDir sits under shared2 Pulsar RetroRewind6`() {
    val ctx = mockWheelWitchContext()

    val dir = DolphinPaths.pulsarRrDir(ctx)

    assertThat(dir.absolutePath)
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Wii/shared2/Pulsar/RetroRewind6"
      )
  }

  @Test
  fun `rrSaveDir sits under pack riivolution save RetroWFC`() {
    val ctx = mockWheelWitchContext()

    val dir = DolphinPaths.rrSaveDir(ctx)

    assertThat(dir.absolutePath)
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch/pack/" +
          "riivolution/save/RetroWFC"
      )
  }

  @Test
  fun `expectedTreeId matches the SAF picker format for the dolphin user folder`() {
    assertThat(DolphinPaths.expectedTreeId())
      .isEqualTo("primary:Android/data/org.dolphinemu.dolphinemu/files")
  }

  @Test
  fun `DOLPHIN_PACKAGE re-exports the constant from DolphinLauncher`() {
    assertThat(DolphinPaths.DOLPHIN_PACKAGE).isEqualTo(DolphinLauncher.DOLPHIN_PACKAGE)
  }

  @Test
  fun `WHEELWITCH_SUBPATH is the WheelWitch segment`() {
    assertThat(DolphinPaths.WHEELWITCH_SUBPATH).isEqualTo("WheelWitch")
  }

  private fun mockWheelWitchContext(
    packageName: String = "com.skiletro.wheelwitch",
    externalFilesDir: File? =
      File("/storage/emulated/0/Android/data/$packageName/files"),
  ): Context {
    val ctx = mockk<Context>(relaxed = true)
    every { ctx.packageName } returns packageName
    every { ctx.getExternalFilesDir(null) } returns externalFilesDir
    return ctx
  }
}
