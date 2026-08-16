package com.skiletro.wheelwitch.util.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.documentfile.provider.DocumentFile
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.data.DolphinTree
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import org.json.JSONObject
import org.junit.jupiter.api.Test

class DolphinLauncherTest {

  @Test
  fun `DOLPHIN_PACKAGE constant is preserved`() {
    // Used by MiiWadInstaller.launchWadFile; must not change.
    assertThat(DolphinLauncher.DOLPHIN_PACKAGE).isEqualTo("org.dolphinemu.dolphinemu")
  }

  @Test
  fun `DOLPHIN_MAIN_ACTIVITY constant is preserved`() {
    assertThat(DolphinLauncher.DOLPHIN_MAIN_ACTIVITY)
      .isEqualTo("org.dolphinemu.dolphinemu.ui.main.MainActivity")
  }

  @Test
  fun `RR_JSON_NAME is the new rr_autostartfile json filename`() {
    // The on-disk launch descriptor is now "rr_autostartfile.json"
    // (renamed from the legacy "RR.json"). DolphinTree.LAUNCH_JSON_NAME
    // re-exports this value; see DolphinTreeTest for that linkage.
    assertThat(DolphinLauncher.RR_JSON_NAME).isEqualTo("rr_autostartfile.json")
  }

  @Test
  fun `LAUNCH_EXTRA_NAME is the AutoStartFile intent extra key`() {
    // Dolphin's MainActivity reads this extra to auto-start a game on
    // launch. Renaming it would break the AutoStartFile path.
    assertThat(DolphinLauncher.LAUNCH_EXTRA_NAME).isEqualTo("AutoStartFile")
  }

  // --- buildLaunchJson ------------------------------------------------

  @Test
  fun `buildLaunchJson produces a parseable JSON object`() {
    val json =
      DolphinLauncher.buildLaunchJson(
        baseFilePath = "/r/rom.iso",
        packRootPath = "/r/pack",
      )
    // Smoke test: the body must be valid JSON. org.json.JSONObject
    // throws on malformed input, so reaching the next assertion is
    // the test.
    val obj = JSONObject(json)
    assertThat(obj.length()).isAtLeast(5)
  }

  @Test
  fun `buildLaunchJson top-level field names match the working reference`() {
    // Field-names regression guard for the
    // dolphin-game-mod-descriptor schema. If this fails, Dolphin's
    // parser will likely reject the descriptor. Note: the
    // org.json library does not preserve insertion order, so we
    // assert the key set, not the key order.
    val json =
      DolphinLauncher.buildLaunchJson(
        baseFilePath = "/r/rom.iso",
        packRootPath = "/r/pack",
      )
    val obj = JSONObject(json)
    assertThat(jsonKeySet(obj))
      .containsExactly("base-file", "display-name", "riivolution", "type", "version")
  }

  @Test
  fun `buildLaunchJson writes the descriptor top-level values`() {
    val json =
      DolphinLauncher.buildLaunchJson(
        baseFilePath = "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch/rom/RMCP01.iso",
        packRootPath = "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch/pack",
      )
    val obj = JSONObject(json)
    assertThat(obj.getString("base-file"))
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch/rom/RMCP01.iso"
      )
    assertThat(obj.getString("display-name")).isEqualTo("Retro Rewind")
    assertThat(obj.getString("type")).isEqualTo("dolphin-game-mod-descriptor")
    // version must be a JSON integer (1), not the string "1".
    assertThat(obj.getInt("version")).isEqualTo(1)
  }

  @Test
  fun `buildLaunchJson patch field names match the descriptor schema`() {
    // Patch field names: options, root, xml.
    val json =
      DolphinLauncher.buildLaunchJson(
        baseFilePath = "/r/rom.iso",
        packRootPath = "/r/pack",
      )
    val patch =
      JSONObject(json)
        .getJSONObject("riivolution")
        .getJSONArray("patches")
        .getJSONObject(0)
    assertThat(jsonKeySet(patch)).containsExactly("options", "root", "xml")
  }

  @Test
  fun `buildLaunchJson writes root and xml from the same packRootPath`() {
    // Path consistency invariant: root and xml share packRootPath
    // as their prefix, so a single physicalRoot input guarantees
    // the two agree. base-file is the caller's responsibility; see
    // the buildLaunchJson kdoc.
    val json =
      DolphinLauncher.buildLaunchJson(
        baseFilePath = "/r/rom.iso",
        packRootPath = "/r/pack",
      )
    val patch =
      JSONObject(json)
        .getJSONObject("riivolution")
        .getJSONArray("patches")
        .getJSONObject(0)
    assertThat(patch.getString("root")).isEqualTo("/r/pack")
    assertThat(patch.getString("xml")).isEqualTo("/r/pack/riivolution/RetroRewind6.xml")
  }

  @Test
  fun `buildLaunchJson joins xmlRelPath to packRootPath with a single forward slash`() {
    val json =
      DolphinLauncher.buildLaunchJson(
        baseFilePath = "/r/rom.iso",
        packRootPath = "/r/pack",
        xmlRelPath = "riivolution/SubDir/Mod.xml",
      )
    val patch =
      JSONObject(json)
        .getJSONObject("riivolution")
        .getJSONArray("patches")
        .getJSONObject(0)
    assertThat(patch.getString("xml")).isEqualTo("/r/pack/riivolution/SubDir/Mod.xml")
  }

  @Test
  fun `buildLaunchJson writes the default Pack1 MyStuff2 Savegame2 options`() {
    val json =
      DolphinLauncher.buildLaunchJson(
        baseFilePath = "/r/rom.iso",
        packRootPath = "/r/pack",
      )
    val options =
      JSONObject(json)
        .getJSONObject("riivolution")
        .getJSONArray("patches")
        .getJSONObject(0)
        .getJSONArray("options")
    assertThat(options.length()).isEqualTo(3)
    // Each option's field set is choice, option-name, section-name.
    for (i in 0 until options.length()) {
      assertThat(jsonKeySet(options.getJSONObject(i)))
        .containsExactly("choice", "option-name", "section-name")
    }
    assertOption(options.getJSONObject(0), name = "Pack", choice = 1)
    assertOption(options.getJSONObject(1), name = "My Stuff", choice = 2)
    assertOption(options.getJSONObject(2), name = "Separate Savegame", choice = 2)
  }

  @Test
  fun `buildLaunchJson customizes choices via parameters`() {
    val json =
      DolphinLauncher.buildLaunchJson(
        baseFilePath = "/r/rom.iso",
        packRootPath = "/r/pack",
        packChoice = 5,
        myStuffChoice = 6,
        separateSavegameChoice = 7,
      )
    val options =
      JSONObject(json)
        .getJSONObject("riivolution")
        .getJSONArray("patches")
        .getJSONObject(0)
        .getJSONArray("options")
    assertOption(options.getJSONObject(0), name = "Pack", choice = 5)
    assertOption(options.getJSONObject(1), name = "My Stuff", choice = 6)
    assertOption(options.getJSONObject(2), name = "Separate Savegame", choice = 7)
  }

  @Test
  fun `buildLaunchJson writes every section-name as the display name`() {
    val json =
      DolphinLauncher.buildLaunchJson(
        baseFilePath = "/r/rom.iso",
        packRootPath = "/r/pack",
      )
    val options =
      JSONObject(json)
        .getJSONObject("riivolution")
        .getJSONArray("patches")
        .getJSONObject(0)
        .getJSONArray("options")
    for (i in 0 until options.length()) {
      assertThat(options.getJSONObject(i).getString("section-name"))
        .isEqualTo("Retro Rewind")
    }
  }

  @Test
  fun `buildLaunchJson writes a single patch in the patches array`() {
    // The descriptor is a single-patch shape. If we ever fan out
    // to multi-patch, the JSON path tests will need an index; call
    // out the constraint here.
    val json =
      DolphinLauncher.buildLaunchJson(
        baseFilePath = "/r/rom.iso",
        packRootPath = "/r/pack",
      )
    val patches = JSONObject(json).getJSONObject("riivolution").getJSONArray("patches")
    assertThat(patches.length()).isEqualTo(1)
  }

  // --- launch --------------------------------------------------------

  @Test
  fun `launch writes a descriptor with base-file pointing at the ROM`() {
    val ctx = mockInstalledDolphin()
    val tree = mockTree()
    val romFile = mockRomFile("RMCP01.iso")
    val written = slot<String>()
    every { tree.writeLaunchJson(capture(written)) } returns mockk(relaxed = true)

    val result = DolphinLauncher.launch(ctx, tree, romFile)

    assertThat(result.isSuccess).isTrue()
    val obj = JSONObject(written.captured)
    assertThat(obj.getString("base-file"))
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch/rom/RMCP01.iso"
      )
  }

  @Test
  fun `launch writes a descriptor with root and xml under the pack dir`() {
    val ctx = mockInstalledDolphin()
    val tree = mockTree()
    val romFile = mockRomFile("RMCE01.rvz")
    val written = slot<String>()
    every { tree.writeLaunchJson(capture(written)) } returns mockk(relaxed = true)

    DolphinLauncher.launch(ctx, tree, romFile)

    val patch =
      JSONObject(written.captured)
        .getJSONObject("riivolution")
        .getJSONArray("patches")
        .getJSONObject(0)
    assertThat(patch.getString("root"))
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch/pack"
      )
    assertThat(patch.getString("xml"))
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch/pack/riivolution/RetroRewind6.xml"
      )
  }

  @Test
  fun `launch fires startActivity exactly once`() {
    val ctx = mockInstalledDolphin()
    val tree = mockTree()
    val romFile = mockRomFile("RMCP01.iso")

    val result = DolphinLauncher.launch(ctx, tree, romFile)

    assertThat(result.isSuccess).isTrue()
    verify(exactly = 1) { ctx.startActivity(any<Intent>()) }
  }

  @Test
  fun `launch honors a custom xmlRelPath`() {
    val ctx = mockInstalledDolphin()
    val tree = mockTree()
    val romFile = mockRomFile("RMCP01.iso")
    val written = slot<String>()
    every { tree.writeLaunchJson(capture(written)) } returns mockk(relaxed = true)

    DolphinLauncher.launch(ctx, tree, romFile, xmlRelPath = "riivolution/SubMod/Mod.xml")

    val patch =
      JSONObject(written.captured)
        .getJSONObject("riivolution")
        .getJSONArray("patches")
        .getJSONObject(0)
    assertThat(patch.getString("xml"))
      .isEqualTo(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/WheelWitch/pack/riivolution/SubMod/Mod.xml"
      )
  }

  @Test
  fun `launch returns failure when Dolphin is not installed`() {
    val ctx = mockk<Context>(relaxed = true)
    // isDolphinInstalled swallows the NameNotFoundException and returns false.
    every {
      ctx.packageManager.getPackageInfo(DolphinLauncher.DOLPHIN_PACKAGE, 0)
    } throws PackageManager.NameNotFoundException()
    val tree = mockTree()
    val romFile = mockRomFile("RMCP01.iso")

    val result = DolphinLauncher.launch(ctx, tree, romFile)

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).isInstanceOf(ActivityNotFoundException::class.java)
  }

  @Test
  fun `launch returns failure when getExternalFilesDir is null`() {
    val ctx = mockk<Context>(relaxed = true)
    every {
      ctx.packageManager.getPackageInfo(DolphinLauncher.DOLPHIN_PACKAGE, 0)
    } returns mockk(relaxed = true)
    every { ctx.packageName } returns "com.skiletro.wheelwitch"
    every { ctx.getExternalFilesDir(null) } returns null
    val tree = mockTree()
    val romFile = mockRomFile("RMCP01.iso")

    val result = DolphinLauncher.launch(ctx, tree, romFile)

    assertThat(result.isFailure).isTrue()
  }

  @Test
  fun `launch returns failure when writeLaunchJson throws`() {
    val ctx = mockInstalledDolphin()
    val tree = mockTree()
    every { tree.writeLaunchJson(any()) } throws IllegalStateException("SAF write failed")
    val romFile = mockRomFile("RMCP01.iso")

    val result = DolphinLauncher.launch(ctx, tree, romFile)

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).hasMessageThat().contains("SAF write failed")
  }

  @Test
  fun `launch does not fire the intent when the descriptor write throws`() {
    val ctx = mockInstalledDolphin()
    val tree = mockTree()
    every { tree.writeLaunchJson(any()) } throws IllegalStateException("boom")
    val romFile = mockRomFile("RMCP01.iso")

    DolphinLauncher.launch(ctx, tree, romFile)

    verify(exactly = 0) { ctx.startActivity(any()) }
  }

  // --- registerRomPathInConfig ----------------------------------------

  @Test
  fun `registerRomPathInConfig writes the rom URI into the ini block`() {
    val ctx = mockInstalledDolphin()
    val tree = mockk<DolphinTree>(relaxed = true)
    val existingIni =
      """
      [General]
      SomeKey = value
      """.trimIndent()
    val written = slot<String>()
    every { tree.readConfigIni() } returns existingIni
    every { tree.writeConfigIni(capture(written)) } returns mockk(relaxed = true)

    DolphinLauncher.registerRomPathInConfig(ctx, tree, "RMCP01.iso")

    val ini = written.captured
    assertThat(ini).contains("[General]")
    assertThat(ini).contains("ISOPaths = 1")
    assertThat(ini)
      .contains(
        "ISOPath0 = content://org.dolphinemu.dolphinemu.user/tree/root%2FWheelWitch%2From"
      )
    assertThat(ini).contains("SomeKey = value")
  }

  @Test
  fun `registerRomPathInConfig is a no-op when the rom URI is already present`() {
    val ctx = mockInstalledDolphin()
    val tree = mockk<DolphinTree>(relaxed = true)
    val existing =
      """
      [General]
      ISOPaths = 1
      ISOPath0 = content://org.dolphinemu.dolphinemu.user/tree/root%2FWheelWitch%2From
      """.trimIndent()
    every { tree.readConfigIni() } returns existing
    every { tree.writeConfigIni(any()) } returns mockk(relaxed = true)

    DolphinLauncher.registerRomPathInConfig(ctx, tree, "RMCP01.iso")

    verify(exactly = 0) { tree.writeConfigIni(any()) }
  }

  @Test
  fun `registerRomPathInConfig throws for an unknown ROM extension`() {
    val ctx = mockInstalledDolphin()
    val tree = mockk<DolphinTree>(relaxed = true)
    val ex =
      runCatching { DolphinLauncher.registerRomPathInConfig(ctx, tree, "ROM.bin") }
        .exceptionOrNull()
    assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `registerRomPathInConfig works on an empty INI`() {
    val ctx = mockInstalledDolphin()
    val tree = mockk<DolphinTree>(relaxed = true)
    val written = slot<String>()
    every { tree.readConfigIni() } returns ""
    every { tree.writeConfigIni(capture(written)) } returns mockk(relaxed = true)

    DolphinLauncher.registerRomPathInConfig(ctx, tree, "RMCE01.rvz")

    assertThat(written.captured)
      .isEqualTo(
        "[General]\n\n" +
          "ISOPaths = 1\n" +
          "ISOPath0 = content://org.dolphinemu.dolphinemu.user/tree/root%2FWheelWitch%2From"
      )
  }

  // --- pickRomFile ----------------------------------------------------

  @Test
  fun `pickRomFile returns the first recognized ROM in the romDir`() {
    val tree = mockk<DolphinTree>(relaxed = true)
    val romDir = mockk<DocumentFile>(relaxed = true)
    every { tree.romDir } returns romDir
    val iso = mockRomFile("RMCP01.iso")
    val rvz = mockRomFile("RMCE01.rvz")
    every { romDir.listFiles() } returns arrayOf(iso, rvz)

    val picked = DolphinLauncher.pickRomFile(tree)

    assertThat(picked).isEqualTo(iso)
  }

  @Test
  fun `pickRomFile ignores files with an unrecognized extension`() {
    val tree = mockk<DolphinTree>(relaxed = true)
    val romDir = mockk<DocumentFile>(relaxed = true)
    every { tree.romDir } returns romDir
    val readme = mockRomFile("README.txt")
    val iso = mockRomFile("RMCP01.iso")
    every { romDir.listFiles() } returns arrayOf(readme, iso)

    val picked = DolphinLauncher.pickRomFile(tree)

    assertThat(picked).isEqualTo(iso)
  }

  @Test
  fun `pickRomFile returns null when no recognized ROM is present`() {
    val tree = mockk<DolphinTree>(relaxed = true)
    val romDir = mockk<DocumentFile>(relaxed = true)
    every { tree.romDir } returns romDir
    every { romDir.listFiles() } returns arrayOf(mockRomFile("notes.txt"))

    val picked = DolphinLauncher.pickRomFile(tree)

    assertThat(picked).isNull()
  }

  // --- startDolphin ---------------------------------------------------

  @Test
  fun `startDolphin fires a bare Dolphin intent`() {
    val ctx = mockInstalledDolphin()

    val started = DolphinLauncher.startDolphin(ctx)

    assertThat(started).isTrue()
    verify(exactly = 1) { ctx.startActivity(any<Intent>()) }
  }

  // --- launchRetroRewind ---------------------------------------------

  @Test
  fun `launchRetroRewind returns DolphinNotInstalled when Dolphin is absent`() {
    val ctx = mockk<Context>(relaxed = true)
    every {
      ctx.packageManager.getPackageInfo(DolphinLauncher.DOLPHIN_PACKAGE, 0)
    } throws PackageManager.NameNotFoundException()

    val result = DolphinLauncher.launchRetroRewind(ctx)

    assertThat(result).isInstanceOf(DolphinLauncher.LaunchResult.DolphinNotInstalled::class.java)
  }

  @Test
  fun `launchRetroRewind returns NoRom when romDir is empty`() {
    val ctx = mockInstalledDolphin()
    val tree = mockk<DolphinTree>(relaxed = true)
    val romDir = mockk<DocumentFile>(relaxed = true)
    every { romDir.listFiles() } returns arrayOf()
    every { tree.romDir } returns romDir

    val result = DolphinLauncher.launchRetroRewind(ctx, tree)

    assertThat(result).isInstanceOf(DolphinLauncher.LaunchResult.NoRom::class.java)
  }

  @Test
  fun `launchRetroRewind returns StorageNotConfigured when no tree is persisted`() {
    val ctx = mockInstalledDolphin()
    mockkStatic("com.skiletro.wheelwitch.data.DolphinTree")
    every { com.skiletro.wheelwitch.data.DolphinTree.fromPersisted(ctx) } returns null

    val result = DolphinLauncher.launchRetroRewind(ctx)

    assertThat(result)
      .isInstanceOf(DolphinLauncher.LaunchResult.StorageNotConfigured::class.java)
    unmockkStatic("com.skiletro.wheelwitch.data.DolphinTree")
  }

  @Test
  fun `launchRetroRewind auto-starts when Dolphin is installed and ROM is present`() {
    val ctx = mockInstalledDolphin()
    val tree = mockk<DolphinTree>(relaxed = true)
    val romDir = mockk<DocumentFile>(relaxed = true)
    val rom = mockRomFile("RMCP01.iso")
    every { tree.romDir } returns romDir
    every { romDir.listFiles() } returns arrayOf(rom)
    every { tree.readConfigIni() } returns null
    every { tree.writeConfigIni(any()) } returns mockk(relaxed = true)
    every { tree.writeLaunchJson(any()) } returns mockk(relaxed = true)

    val result = DolphinLauncher.launchRetroRewind(ctx, tree)

    assertThat(result).isInstanceOf(DolphinLauncher.LaunchResult.AutoStarted::class.java)
    verify { tree.writeConfigIni(any()) }
    verify { tree.writeLaunchJson(any()) }
    verify { ctx.startActivity(any<Intent>()) }
  }

  @Test
  fun `launchRetroRewind falls back to bare Dolphin when the descriptor write throws`() {
    val ctx = mockInstalledDolphin()
    val tree = mockk<DolphinTree>(relaxed = true)
    val romDir = mockk<DocumentFile>(relaxed = true)
    val rom = mockRomFile("RMCP01.iso")
    every { tree.romDir } returns romDir
    every { romDir.listFiles() } returns arrayOf(rom)
    every { tree.readConfigIni() } returns null
    every { tree.writeConfigIni(any()) } returns mockk(relaxed = true)
    every { tree.writeLaunchJson(any()) } throws IllegalStateException("SAF boom")

    val result = DolphinLauncher.launchRetroRewind(ctx, tree)

    assertThat(result)
      .isInstanceOf(DolphinLauncher.LaunchResult.FallbackStarted::class.java)
    // writeLaunchJson threw before the auto-start intent was fired,
    // so only the fallback bare intent reaches startActivity.
    verify(exactly = 1) { ctx.startActivity(any<Intent>()) }
  }

  @Test
  fun `startDolphin returns false when Dolphin is not installed`() {
    val ctx = mockk<Context>(relaxed = true)
    every {
      ctx.packageManager.getPackageInfo(DolphinLauncher.DOLPHIN_PACKAGE, 0)
    } throws PackageManager.NameNotFoundException()

    val started = DolphinLauncher.startDolphin(ctx)

    assertThat(started).isFalse()
  }

  // --- dolphinVersionName --------------------------------------------

  @Test
  fun `dolphinVersionName returns the installed versionName`() {
    val ctx = mockk<Context>(relaxed = true)
    // PackageInfo.versionName is a public field, not something MockK
    // can stub, so use a real instance and set the field directly.
    val info = PackageInfo()
    info.versionName = "2606-299"
    every { ctx.packageManager.getPackageInfo(DolphinLauncher.DOLPHIN_PACKAGE, 0) } returns info

    assertThat(DolphinLauncher.dolphinVersionName(ctx)).isEqualTo("2606-299")
  }

  @Test
  fun `dolphinVersionName returns null when Dolphin is not installed`() {
    val ctx = mockk<Context>(relaxed = true)
    every {
      ctx.packageManager.getPackageInfo(DolphinLauncher.DOLPHIN_PACKAGE, 0)
    } throws PackageManager.NameNotFoundException()

    assertThat(DolphinLauncher.dolphinVersionName(ctx)).isNull()
  }

  @Test
  fun `DOLPHIN_DOWNLOAD_URL points at the official downloads page`() {
    assertThat(DolphinLauncher.DOLPHIN_DOWNLOAD_URL).isEqualTo("https://dolphin-emu.org/download/")
  }

  // --- helpers --------------------------------------------------------

  private fun assertOption(obj: JSONObject, name: String, choice: Int) {
    assertThat(obj.getString("option-name")).isEqualTo(name)
    assertThat(obj.getInt("choice")).isEqualTo(choice)
  }

  private fun jsonKeySet(obj: JSONObject): Set<String> {
    val out = mutableSetOf<String>()
    val it = obj.keys()
    while (it.hasNext()) out.add(it.next())
    return out
  }

  /**
   * A `Context` mock that reports Dolphin as installed and returns a
   * real [File] for [Context.getExternalFilesDir]. The returned path
   * contains the WheelWitch package name so
   * [com.skiletro.wheelwitch.data.DolphinPaths.physicalRoot] can
   * package-swap to the Dolphin user folder.
   */
  private fun mockInstalledDolphin(): Context {
    val ctx = mockk<Context>(relaxed = true)
    every {
      ctx.packageManager.getPackageInfo(DolphinLauncher.DOLPHIN_PACKAGE, 0)
    } returns mockk<PackageInfo>(relaxed = true)
    every { ctx.packageName } returns "com.skiletro.wheelwitch"
    every { ctx.getExternalFilesDir(null) } returns
      File("/storage/emulated/0/Android/data/com.skiletro.wheelwitch/files")
    return ctx
  }

  private fun mockTree(): DolphinTree = mockk(relaxed = true)

  private fun mockRomFile(name: String): DocumentFile {
    val file = mockk<DocumentFile>(relaxed = true)
    every { file.name } returns name
    return file
  }
}
