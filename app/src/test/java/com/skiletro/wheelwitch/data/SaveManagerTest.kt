package com.skiletro.wheelwitch.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.data.SaveManager.Region
import com.skiletro.wheelwitch.model.SemVersion
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.Test

class SaveManagerTest {

  // --- region / listRegions / hasSave (per-region API for Licenses) ---

  @Test
  fun `regionFromRomFileName returns correct region for all three codes`() {
    assertThat(SaveManager.regionFromRomFileName("RMCP01.iso")).isEqualTo(Region.PAL)
    assertThat(SaveManager.regionFromRomFileName("RMCE01.rvz")).isEqualTo(Region.USA)
    assertThat(SaveManager.regionFromRomFileName("RMCJ01.wbfs")).isEqualTo(Region.JPN)
  }

  @Test
  fun `regionFromRomFileName is case-insensitive`() {
    assertThat(SaveManager.regionFromRomFileName("rmcp01.iso")).isEqualTo(Region.PAL)
    assertThat(SaveManager.regionFromRomFileName("RmCe01.rvz")).isEqualTo(Region.USA)
  }

  @Test
  fun `regionFromRomFileName returns null for unknown prefix`() {
    assertThat(SaveManager.regionFromRomFileName("random.iso")).isNull()
    assertThat(SaveManager.regionFromRomFileName("")).isNull()
    assertThat(SaveManager.regionFromRomFileName("MKW.iso")).isNull()
  }

  @Test
  fun `listRegions returns distinct regions present in romDir`() {
    val tree = mockk<DolphinTree>(relaxed = true)
    val romDir = mockk<DocumentFile>(relaxed = true)
    every { tree.romDir } returns romDir
    every { romDir.listFiles() } returns
        arrayOf(
            mockRomFile("RMCP01.iso"),
            mockRomFile("RMCE01.rvz"),
            mockRomFile("RMCP01.iso"),
            mockRomFile("README.txt"),
        )

    val regions = SaveManager.listRegions(tree)

    assertThat(regions).containsExactly(Region.PAL, Region.USA).inOrder()
  }

  @Test
  fun `hasSave returns true when rksys dot dat exists for the region`() {
    val chain = setupSaveChain(region = Region.PAL, fileExists = true)
    assertThat(SaveManager.hasSave(chain.tree, Region.PAL)).isTrue()
  }

  @Test
  fun `hasSave returns false when rksys dot dat is missing for the region`() {
    val chain = setupSaveChain(region = Region.PAL, fileExists = false)
    assertThat(SaveManager.hasSave(chain.tree, Region.PAL)).isFalse()
  }

  // --- userDataPathPrefixes (shared with the pack-update carve-out) --

  @Test
  fun `userDataPathPrefixes contains all three protected directories with trailing slashes`() {
    assertThat(SaveManager.userDataPathPrefixes)
        .containsExactly(
            "riivolution/save/",
            "Wii/shared2/menu/FaceLib/",
            "Wii/shared2/Pulsar/RetroRewind6/",
        )
  }

  @Test
  fun `SAVE_PRESERVE_PREFIX is the first userDataPathPrefixes entry for backward compat`() {
    // The pack-update carve-out in DolphinTree.writeZipEntry still
    // references SAVE_PRESERVE_PREFIX; the unified list is the
    // source of truth and includes it.
    assertThat(SaveManager.userDataPathPrefixes.first()).isEqualTo(SaveManager.SAVE_PRESERVE_PREFIX)
  }

  // --- hasAnySave --------------------------------------------------------

  @Test
  fun `hasAnySave is false when the tree is empty`() {
    val tree = mockk<DolphinTree>(relaxed = true)
    // The hasSave helper for each region walks packDir/riivolution/save/RetroWFC/<code>/rksys.dat.
    // A relaxed mock returns null for findFile calls, so the chain doesn't resolve to a file.
    every { tree.packDir.findFile(any<String>()) } returns null
    every { tree.faceLibDir } returns null
    every { tree.pulsarRrDir } returns null

    assertThat(SaveManager.hasAnySave(tree)).isFalse()
  }

  @Test
  fun `hasAnySave is true when at least one region's rksys dat exists`() {
    val chain = setupSaveChain(region = Region.PAL, fileExists = true)
    every { chain.tree.faceLibDir } returns null
    every { chain.tree.pulsarRrDir } returns null

    assertThat(SaveManager.hasAnySave(chain.tree)).isTrue()
  }

  @Test
  fun `hasAnySave is true when only the Mii DB exists`() {
    val env = setupBackupEnv(palExists = false, includeFaceLib = true)
    every { env.tree.pulsarRrDir } returns null

    assertThat(SaveManager.hasAnySave(env.tree)).isTrue()
  }

  @Test
  fun `hasAnySave is true when only Pulsar pul files exist`() {
    val env = setupBackupEnv(palExists = false, includePul = true)

    assertThat(SaveManager.hasAnySave(env.tree)).isTrue()
  }

  @Test
  fun `hasAnySave is true when only ghosts exist`() {
    val env = setupBackupEnv(palExists = false, includeGhosts = true)

    assertThat(SaveManager.hasAnySave(env.tree)).isTrue()
  }

  @Test
  fun `hasAnySave is false when only a vanilla NAND save exists`() {
    // Vanilla NAND saves are outside the backup scope; they alone
    // must not enable the backup UI.
    val env = setupBackupEnv(palExists = false, includeVanillaSaves = true)
    every { env.tree.faceLibDir } returns null
    every { env.tree.pulsarRrDir } returns null

    assertThat(SaveManager.hasAnySave(env.tree)).isFalse()
  }

  @Test
  fun `hasAnySave is false when only a patched ISO save exists`() {
    val env = setupBackupEnv(palExists = false, includePatchedIso = true)
    every { env.tree.faceLibDir } returns null
    every { env.tree.pulsarRrDir } returns null

    assertThat(SaveManager.hasAnySave(env.tree)).isFalse()
  }

  // --- backup -----------------------------------------------------------

  @Test
  fun `backup writes a manifest plus per-region rksys files to the user-picked zip`() = runTest {
    val env = setupBackupEnv(palExists = true, usaExists = false, jpnExists = true)
    val dest = mockk<Uri>(relaxed = true)
    val destOutput = ByteArrayOutputStream()
    every { env.resolver.openOutputStream(dest) } returns destOutput

    val result = SaveManager.backup(env.tree, dest)

    assertThat(result.isSuccess).isTrue()
    val summary = result.getOrThrow()
    assertThat(summary.rksys).isEqualTo(2)
    val entries = readZipEntries(destOutput.toByteArray())
    val names = entries.map { it.name }
    assertThat(names).contains("manifest.json")
    assertThat(names).contains("RetroWFC/RMCP/rksys.dat")
    assertThat(names).contains("RetroWFC/RMCJ/rksys.dat")
    assertThat(names).doesNotContain("RetroWFC/RMCE/rksys.dat")
    val manifest = JSONObject(String(entries.first { it.name == "manifest.json" }.bytes))
    assertThat(manifest.getString("type")).isEqualTo(SaveManager.BACKUP_TYPE)
    assertThat(manifest.getInt("version")).isEqualTo(SaveManager.BACKUP_FORMAT_VERSION)
    val contents = manifest.getJSONObject("contents")
    val retroWfc =
        contents.getJSONArray("retroWfc").let { arr -> List(arr.length()) { arr.getString(it) } }
    assertThat(retroWfc).containsExactly("RMCP", "RMCJ")
    assertThat(contents.getJSONArray("vanillaSaves").length()).isEqualTo(0)
    assertThat(contents.getBoolean("patchedIso")).isFalse()
  }

  @Test
  fun `backup deletes destination when writing fails`() = runTest {
    val env = setupBackupEnv(palExists = true)
    val dest = mockk<Uri>(relaxed = true)
    every { env.resolver.openOutputStream(dest) } returns ByteArrayOutputStream()
    every { env.resolver.openInputStream(env.regionFile("RMCP").uri) } returns
        FailingAfterFirstReadInputStream()
    mockkStatic(DocumentsContract::class)
    every { DocumentsContract.deleteDocument(env.resolver, dest) } returns true

    try {
      val result = SaveManager.backup(env.tree, dest)

      assertThat(result.isFailure).isTrue()
      assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("backup read failed")
      io.mockk.verify(exactly = 1) { DocumentsContract.deleteDocument(env.resolver, dest) }
    } finally {
      unmockkStatic(DocumentsContract::class)
    }
  }

  @Test
  fun `backup includes the Mii DB and all Pulsar pul files when present`() = runTest {
    val env = setupBackupEnv(palExists = true, includeFaceLib = true, includePul = true)
    val dest = mockk<Uri>(relaxed = true)
    val destOutput = ByteArrayOutputStream()
    every { env.resolver.openOutputStream(dest) } returns destOutput

    val result = SaveManager.backup(env.tree, dest)

    assertThat(result.isSuccess).isTrue()
    val summary = result.getOrThrow()
    assertThat(summary.faceLib).isTrue()
    assertThat(summary.pulsar).isEqualTo(3)
    val entries = readZipEntries(destOutput.toByteArray())
    val names = entries.map { it.name }
    assertThat(names).contains("Wii/shared2/menu/FaceLib/RFL_DB.dat")
    assertThat(names).contains("Wii/shared2/Pulsar/RetroRewind6/RRRating.pul")
    assertThat(names).contains("Wii/shared2/Pulsar/RetroRewind6/RRGameSettings.pul")
    assertThat(names).contains("Wii/shared2/Pulsar/RetroRewind6/RRSettings.pul")
    val manifest = JSONObject(String(entries.first { it.name == "manifest.json" }.bytes))
    val pulsar =
        manifest.getJSONObject("contents").getJSONArray("pulsar").let { arr ->
          List(arr.length()) { arr.getString(it) }
        }
    assertThat(pulsar).containsExactly("RRRating.pul", "RRGameSettings.pul", "RRSettings.pul")
  }

  @Test
  fun `backup recursively copies Ghosts contents when present`() = runTest {
    val env = setupBackupEnv(palExists = true, includeGhosts = true)
    val dest = mockk<Uri>(relaxed = true)
    val destOutput = ByteArrayOutputStream()
    every { env.resolver.openOutputStream(dest) } returns destOutput

    val result = SaveManager.backup(env.tree, dest)

    assertThat(result.isSuccess).isTrue()
    val summary = result.getOrThrow()
    assertThat(summary.ghosts).isGreaterThan(0)
    val entries = readZipEntries(destOutput.toByteArray())
    val ghostEntries =
        entries.map { it.name }.filter { it.startsWith("Wii/shared2/Pulsar/RetroRewind6/Ghosts/") }
    assertThat(ghostEntries).isNotEmpty()
  }

  @Test
  fun `backup stamps the installed RR version into the manifest`() = runTest {
    val env = setupBackupEnv(palExists = true)
    every { env.tree.readVersion() } returns SemVersion(6, 2, 0)
    val dest = mockk<Uri>(relaxed = true)
    val destOutput = ByteArrayOutputStream()
    every { env.resolver.openOutputStream(dest) } returns destOutput

    val result = SaveManager.backup(env.tree, dest)

    assertThat(result.isSuccess).isTrue()
    val entries = readZipEntries(destOutput.toByteArray())
    val manifest = JSONObject(String(entries.first { it.name == "manifest.json" }.bytes))
    assertThat(manifest.getString("rrVersion")).isEqualTo("6.2.0")
  }

  @Test
  fun `backup omits rrVersion when the pack version is unknown`() = runTest {
    val env = setupBackupEnv(palExists = true)
    every { env.tree.readVersion() } returns null
    val dest = mockk<Uri>(relaxed = true)
    val destOutput = ByteArrayOutputStream()
    every { env.resolver.openOutputStream(dest) } returns destOutput

    val result = SaveManager.backup(env.tree, dest)

    assertThat(result.isSuccess).isTrue()
    val entries = readZipEntries(destOutput.toByteArray())
    val manifest = JSONObject(String(entries.first { it.name == "manifest.json" }.bytes))
    assertThat(manifest.has("rrVersion")).isFalse()
  }

  @Test
  fun `backup excludes vanilla NAND saves even when present`() = runTest {
    val env = setupBackupEnv(palExists = true, includeVanillaSaves = true)
    val dest = mockk<Uri>(relaxed = true)
    val destOutput = ByteArrayOutputStream()
    every { env.resolver.openOutputStream(dest) } returns destOutput

    val result = SaveManager.backup(env.tree, dest)

    assertThat(result.isSuccess).isTrue()
    val entries = readZipEntries(destOutput.toByteArray())
    val names = entries.map { it.name }
    assertThat(names).doesNotContain("Wii/title/00010004/524d4350/data/rksys.dat")
    assertThat(names).contains("RetroWFC/RMCP/rksys.dat")
  }

  @Test
  fun `backup excludes the patched ISO save even when present`() = runTest {
    val env = setupBackupEnv(palExists = true, includePatchedIso = true)
    val dest = mockk<Uri>(relaxed = true)
    val destOutput = ByteArrayOutputStream()
    every { env.resolver.openOutputStream(dest) } returns destOutput

    val result = SaveManager.backup(env.tree, dest)

    assertThat(result.isSuccess).isTrue()
    val entries = readZipEntries(destOutput.toByteArray())
    assertThat(entries.map { it.name }).doesNotContain("Wii/title/00010004/524d4352/data/rksys.dat")
  }

  // --- restore ----------------------------------------------------------

  @Test
  fun `restore rejects a zip whose manifest is missing`() = runTest {
    val tree = mockk<DolphinTree>(relaxed = true)
    val resolver = mockk<ContentResolver>(relaxed = true)
    every { tree.resolver } returns resolver
    val source = mockk<Uri>(relaxed = true)
    val bytes =
        ByteArrayOutputStream().use { baos ->
          java.util.zip.ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("some/random/file.txt"))
            zos.write("x".encodeToByteArray())
            zos.closeEntry()
          }
          baos.toByteArray()
        }
    every { resolver.openInputStream(source) } returns ByteArrayInputStream(bytes)

    val result = SaveManager.restore(tree, source)

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message).contains("not a WheelWitch save backup")
  }

  @Test
  fun `restore rejects a zip whose type tag is wrong`() = runTest {
    val tree = mockk<DolphinTree>(relaxed = true)
    val resolver = mockk<ContentResolver>(relaxed = true)
    every { tree.resolver } returns resolver
    val source = mockk<Uri>(relaxed = true)
    val bytes =
        ByteArrayOutputStream().use { baos ->
          java.util.zip.ZipOutputStream(baos).use { zos ->
            val obj =
                JSONObject().apply {
                  put("type", "some-other-app")
                  put("version", 1)
                }
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(obj.toString().encodeToByteArray())
            zos.closeEntry()
          }
          baos.toByteArray()
        }
    every { resolver.openInputStream(source) } returns ByteArrayInputStream(bytes)

    val result = SaveManager.restore(tree, source)

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message).contains("not a WheelWitch save backup")
  }

  @Test
  fun `restore rejects a zip whose version is higher than supported`() = runTest {
    val tree = mockk<DolphinTree>(relaxed = true)
    val resolver = mockk<ContentResolver>(relaxed = true)
    every { tree.resolver } returns resolver
    val source = mockk<Uri>(relaxed = true)
    val bytes =
        ByteArrayOutputStream().use { baos ->
          java.util.zip.ZipOutputStream(baos).use { zos ->
            val obj =
                JSONObject().apply {
                  put("type", SaveManager.BACKUP_TYPE)
                  put("version", 99)
                }
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(obj.toString().encodeToByteArray())
            zos.closeEntry()
          }
          baos.toByteArray()
        }
    every { resolver.openInputStream(source) } returns ByteArrayInputStream(bytes)

    val result = SaveManager.restore(tree, source)

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message).contains("newer version of Wheel Witch")
  }

  @Test
  fun `restore round-trips a backup and reports per-section counts`() = runTest {
    val env = setupBackupEnv(palExists = true, includeFaceLib = true, includePul = true)
    every { env.tree.readVersion() } returns SemVersion(6, 2, 0)
    val source = mockk<Uri>(relaxed = true)
    setupRestoreEnv(env)

    // Build a backup zip on the fly and stream it into restore.
    val backupOutput = ByteArrayOutputStream()
    every { env.resolver.openOutputStream(any()) } returns backupOutput
    SaveManager.backup(env.tree, mockk(relaxed = true))
    val backupBytes = backupOutput.toByteArray()
    every { env.resolver.openInputStream(source) } returns ByteArrayInputStream(backupBytes)

    val result = SaveManager.restore(env.tree, source)

    assertThat(result.isSuccess).isTrue()
    val summary = result.getOrThrow()
    assertThat(summary.rksys).isEqualTo(1)
    assertThat(summary.faceLib).isTrue()
    assertThat(summary.pulsar).isEqualTo(3)
  }

  @Test
  fun `restore still restores vanilla saves from older backup zips`() = runTest {
    // Vanilla NAND saves are excluded from new backups, but restore
    // keeps handling them so existing backups round-trip.
    val env = setupBackupEnv(includeVanillaSaves = true)
    val source = mockk<Uri>(relaxed = true)
    setupRestoreEnv(env)

    val backupBytes =
        ByteArrayOutputStream().use { baos ->
          java.util.zip.ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestJson(rrVersion = null).toString().encodeToByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("Wii/title/00010004/524d4350/data/rksys.dat"))
            zos.write("vanilla-save".encodeToByteArray())
            zos.closeEntry()
          }
          baos.toByteArray()
        }
    every { env.resolver.openInputStream(source) } returns ByteArrayInputStream(backupBytes)

    val result = SaveManager.restore(env.tree, source)

    assertThat(result.isSuccess).isTrue()
    val summary = result.getOrThrow()
    assertThat(summary.vanillaSaves).isEqualTo(1)
    assertThat(summary.patchedIso).isFalse()
  }

  @Test
  fun `restore still restores the patched ISO save from older backup zips`() = runTest {
    val env = setupBackupEnv(includePatchedIso = true)
    val source = mockk<Uri>(relaxed = true)
    setupRestoreEnv(env)

    val backupBytes =
        ByteArrayOutputStream().use { baos ->
          java.util.zip.ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestJson(rrVersion = null).toString().encodeToByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("Wii/title/00010004/524d4352/data/rksys.dat"))
            zos.write("patched-save".encodeToByteArray())
            zos.closeEntry()
          }
          baos.toByteArray()
        }
    every { env.resolver.openInputStream(source) } returns ByteArrayInputStream(backupBytes)

    val result = SaveManager.restore(env.tree, source)

    assertThat(result.isSuccess).isTrue()
    val summary = result.getOrThrow()
    assertThat(summary.vanillaSaves).isEqualTo(0)
    assertThat(summary.patchedIso).isTrue()
  }

  @Test
  fun `restore skips entries with path traversal and absolute paths`() = runTest {
    val env = setupBackupEnv(palExists = true)
    val source = mockk<Uri>(relaxed = true)
    setupRestoreEnv(env)

    val backupBytes =
        ByteArrayOutputStream().use { baos ->
          java.util.zip.ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(
                manifestJson(rrVersion = null, retroWfc = listOf("RMCP"))
                    .toString()
                    .encodeToByteArray()
            )
            zos.closeEntry()
            // Safe entry.
            zos.putNextEntry(ZipEntry("RetroWFC/RMCP/rksys.dat"))
            zos.write("safe-data".encodeToByteArray())
            zos.closeEntry()
            // Traversal entries — should all be skipped.
            zos.putNextEntry(ZipEntry("../../escape.txt"))
            zos.write("bad".encodeToByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("./RetroWFC/RMCP/../../sneaky.dat"))
            zos.write("bad2".encodeToByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("/etc/passwd"))
            zos.write("abs".encodeToByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("./sneaky.txt"))
            zos.write("dot".encodeToByteArray())
            zos.closeEntry()
          }
          baos.toByteArray()
        }
    every { env.resolver.openInputStream(source) } returns ByteArrayInputStream(backupBytes)

    val result = SaveManager.restore(env.tree, source)

    assertThat(result.isSuccess).isTrue()
    // Only the safe RetroWFC entry was processed; traversal entries were skipped.
    val summary = result.getOrThrow()
    assertThat(summary.rksys).isEqualTo(1)
    assertThat(summary.vanillaSaves).isEqualTo(0)
    assertThat(summary.faceLib).isFalse()
    assertThat(summary.pulsar).isEqualTo(0)
    assertThat(summary.ghosts).isEqualTo(0)
  }

  // --- RRGameSettings version gate -------------------------------------

  @Test
  fun `restore writes RRGameSettings pul when the backup RR version matches the installed pack`() =
      runTest {
        val env = setupBackupEnv(palExists = false, includePul = true)
        every { env.tree.readVersion() } returns SemVersion(6, 2, 0)
        val source = mockk<Uri>(relaxed = true)
        val gsOutput = ByteArrayOutputStream()
        every { env.resolver.openOutputStream(env.pulFile("RRGameSettings.pul").uri) } returns
            gsOutput

        sourceUriWith(source, env, rrVersion = "6.2.0")
        val result = SaveManager.restore(env.tree, source)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().pulsar).isEqualTo(3)
        assertThat(gsOutput.toByteArray()).isEqualTo("RRGameSettings.pul".encodeToByteArray())
      }

  @Test
  fun `restore skips RRGameSettings pul when the backup RR version differs from the installed pack`() =
      runTest {
        val env = setupBackupEnv(palExists = false, includePul = true)
        every { env.tree.readVersion() } returns SemVersion(6, 3, 0)
        val source = mockk<Uri>(relaxed = true)
        val gsUri = env.pulFile("RRGameSettings.pul").uri
        val ratingOutput = ByteArrayOutputStream()
        every { env.resolver.openOutputStream(env.pulFile("RRRating.pul").uri) } returns
            ratingOutput

        sourceUriWith(source, env, rrVersion = "6.2.0")
        val result = SaveManager.restore(env.tree, source)

        assertThat(result.isSuccess).isTrue()
        // RRRating.pul still restores; RRGameSettings.pul does not.
        assertThat(ratingOutput.toByteArray()).isEqualTo("RRRating.pul".encodeToByteArray())
        verify(exactly = 0) { env.resolver.openOutputStream(gsUri) }
      }

  @Test
  fun `restore skips RRGameSettings pul when the backup has no rrVersion stamp`() = runTest {
    val env = setupBackupEnv(palExists = false, includePul = true)
    every { env.tree.readVersion() } returns SemVersion(6, 2, 0)
    val source = mockk<Uri>(relaxed = true)
    val gsUri = env.pulFile("RRGameSettings.pul").uri

    sourceUriWith(source, env, rrVersion = null)
    val result = SaveManager.restore(env.tree, source)

    assertThat(result.isSuccess).isTrue()
    verify(exactly = 0) { env.resolver.openOutputStream(gsUri) }
  }

  @Test
  fun `restore skips RRGameSettings pul when the pack is not installed`() = runTest {
    val env = setupBackupEnv(palExists = false, includePul = true)
    every { env.tree.readVersion() } returns null
    val source = mockk<Uri>(relaxed = true)
    val gsUri = env.pulFile("RRGameSettings.pul").uri

    sourceUriWith(source, env, rrVersion = "6.2.0")
    val result = SaveManager.restore(env.tree, source)

    assertThat(result.isSuccess).isTrue()
    verify(exactly = 0) { env.resolver.openOutputStream(gsUri) }
  }

  @Test
  fun `restore skips RRGameSettings pul when the backup rrVersion is unparseable`() = runTest {
    val env = setupBackupEnv(palExists = false, includePul = true)
    every { env.tree.readVersion() } returns SemVersion(6, 2, 0)
    val source = mockk<Uri>(relaxed = true)
    val gsUri = env.pulFile("RRGameSettings.pul").uri

    sourceUriWith(source, env, rrVersion = "garbage")
    val result = SaveManager.restore(env.tree, source)

    assertThat(result.isSuccess).isTrue()
    verify(exactly = 0) { env.resolver.openOutputStream(gsUri) }
  }

  // --- delete -----------------------------------------------------------

  @Test
  fun `delete wipes per-region rksys the Mii DB and all pul files`() = runTest {
    val env =
        setupBackupEnv(palExists = true, usaExists = true, includeFaceLib = true, includePul = true)
    every { env.resolver.openOutputStream(any()) } returns ByteArrayOutputStream()

    val result = SaveManager.delete(env.tree)

    assertThat(result.isSuccess).isTrue()
    // Region files are deleted.
    verify { env.regionFile("RMCP").delete() }
    verify { env.regionFile("RMCE").delete() }
    // RFL_DB.dat is deleted.
    verify { env.faceLibFile.delete() }
    // Each pul file is deleted.
    verify { env.pulFile("RRRating.pul").delete() }
    verify { env.pulFile("RRGameSettings.pul").delete() }
    verify { env.pulFile("RRSettings.pul").delete() }
  }

  @Test
  fun `delete is idempotent when nothing exists`() = runTest {
    val tree = mockk<DolphinTree>(relaxed = true)
    every { tree.packDir.findFile(any<String>()) } returns null
    every { tree.faceLibDir } returns null
    every { tree.pulsarRrDir } returns null

    val result = SaveManager.delete(tree)

    assertThat(result.isSuccess).isTrue()
  }

  // --- helpers ---------------------------------------------------------

  /** Holds the mocks wired up by [setupSaveChain] so individual tests can inspect them. */
  private class MockSaveChain(
      val tree: DolphinTree,
      val resolver: ContentResolver,
      val packDir: DocumentFile,
      val regionDir: DocumentFile,
      val saveFile: DocumentFile,
  )

  /**
   * Wires up a save chain of the form `packDir/riivolution/save/RetroWFC/<regionCode>/rksys.dat`.
   * The intermediate directory documents are explicitly marked as directories (relaxed mocks
   * default to `isDirectory = false`, which would make the production `navigateOrCreate` try to
   * create a new directory and break the chain).
   */
  private fun setupSaveChain(
      region: Region,
      fileExists: Boolean,
      content: ByteArray = ByteArray(0),
  ): MockSaveChain {
    val tree = mockk<DolphinTree>(relaxed = true)
    val resolver = mockk<ContentResolver>(relaxed = true)
    val packDir = mockk<DocumentFile>(relaxed = true)
    val dir1 = mockDir("riivolution")
    val dir2 = mockDir("save")
    val dir3 = mockDir("RetroWFC")
    val dir4 = mockDir(region.code)
    val saveFile = mockk<DocumentFile>(relaxed = true)
    val saveFileUri = mockk<Uri>(relaxed = true)

    every { tree.resolver } returns resolver
    every { tree.packDir } returns packDir

    every { packDir.findFile("riivolution") } returns dir1
    every { dir1.findFile("save") } returns dir2
    every { dir2.findFile("RetroWFC") } returns dir3
    every { dir3.findFile(region.code) } returns dir4
    every { dir4.findFile(SaveManager.SAVE_FILE_NAME) } returns saveFile
    every { dir4.createFile(any(), any()) } returns saveFile
    every { saveFile.exists() } returns fileExists
    every { saveFile.uri } returns saveFileUri
    every { resolver.openInputStream(saveFileUri) } returns ByteArrayInputStream(content)
    every { resolver.openOutputStream(saveFileUri) } returns ByteArrayOutputStream()

    return MockSaveChain(tree, resolver, packDir, dir4, saveFile)
  }

  private class FailingAfterFirstReadInputStream : InputStream() {
    private var firstRead = true

    override fun read(): Int {
      if (firstRead) {
        firstRead = false
        return 'x'.code
      }
      throw IOException("backup read failed")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (firstRead) {
        firstRead = false
        buffer[offset] = 'x'.code.toByte()
        return 1
      }
      throw IOException("backup read failed")
    }
  }

  /** A DocumentFile mock with `isDirectory = true` so `navigateOrCreate` keeps the chain. */
  private fun mockDir(name: String): DocumentFile {
    val dir = mockk<DocumentFile>(relaxed = true)
    every { dir.name } returns name
    every { dir.isDirectory } returns true
    return dir
  }

  private fun mockRomFile(name: String): DocumentFile {
    val file = mockk<DocumentFile>(relaxed = true)
    every { file.name } returns name
    return file
  }

  // --- backup / restore / delete environment --------------------------

  /**
   * The minimum set of mocks the unified [SaveManager.backup], [SaveManager.restore], and
   * [SaveManager.delete] need. Stores the per-region `rksys.dat` files, the Mii DB, the three
   * Pulsar pul files, and (optionally) a small Ghosts tree under
   * `Wii/shared2/Pulsar/RetroRewind6/Ghosts/`.
   */
  private class BackupEnv(
      val tree: DolphinTree,
      val resolver: ContentResolver,
      val packDir: DocumentFile,
      val regionFiles: Map<Region, DocumentFile>,
      val faceLibFile: DocumentFile,
      val pulFiles: Map<String, DocumentFile>,
      val ghostsDir: DocumentFile?,
      val wiiDir: DocumentFile,
      val titleDir: DocumentFile,
  ) {
    fun regionFile(code: String): DocumentFile =
        regionFiles[Region.entries.first { it.code == code }] ?: error("region $code not in env")

    fun pulFile(name: String): DocumentFile = pulFiles[name] ?: error("pul file $name not in env")
  }

  private fun setupBackupEnv(
      palExists: Boolean = false,
      usaExists: Boolean = false,
      jpnExists: Boolean = false,
      includeFaceLib: Boolean = false,
      includePul: Boolean = false,
      includeGhosts: Boolean = false,
      includeVanillaSaves: Boolean = false,
      includePatchedIso: Boolean = false,
  ): BackupEnv {
    val tree = mockk<DolphinTree>(relaxed = true)
    val resolver = mockk<ContentResolver>(relaxed = true)
    val packDir = mockk<DocumentFile>(relaxed = true)
    val rootDir = mockk<DocumentFile>(relaxed = true)
    every { tree.resolver } returns resolver
    every { tree.packDir } returns packDir
    every { tree.root } returns rootDir

    // Per-region rksys.dat
    val regionFiles = mutableMapOf<Region, DocumentFile>()
    for (region in Region.entries) {
      val dir = mockDir(region.code)
      val file = mockk<DocumentFile>(relaxed = true)
      val fileUri = mockk<Uri>(relaxed = true)
      every { file.name } returns SaveManager.SAVE_FILE_NAME
      every { file.uri } returns fileUri
      val exists =
          when (region) {
            Region.PAL -> palExists
            Region.USA -> usaExists
            Region.JPN -> jpnExists
            Region.KOR -> false
          }
      every { file.exists() } returns exists
      every { file.isFile } returns exists
      every { dir.findFile(SaveManager.SAVE_FILE_NAME) } returns file
      every { dir.createFile(any(), any()) } returns file
      every { file.delete() } returns true
      every { resolver.openInputStream(fileUri) } returns
          ByteArrayInputStream("region-${region.code}".encodeToByteArray())
      every { resolver.openOutputStream(fileUri) } returns ByteArrayOutputStream()
      regionFiles[region] = file
    }
    // pack/riivolution/save/RetroWFC/<code>/ chain
    val retroWfcDir = mockDir("RetroWFC")
    val saveDir = mockDir("save")
    val riivDir = mockDir("riivolution")
    every { packDir.findFile("riivolution") } returns riivDir
    every { riivDir.findFile("save") } returns saveDir
    every { saveDir.findFile("RetroWFC") } returns retroWfcDir
    // The per-region navigation chain the production code walks
    // (pack/riivolution/save/RetroWFC/<code>) — the inner walk.
    for (region in Region.entries) {
      val regionDir = mockDir(region.code)
      every { regionDir.findFile(SaveManager.SAVE_FILE_NAME) } returns regionFiles[region]
      every { regionDir.createFile(any(), any()) } returns regionFiles[region]
      every { retroWfcDir.findFile(region.code) } returns regionDir
    }

    // FaceLib + Mii DB
    val faceLibDir = mockDir("FaceLib")
    val menuDir = mockDir("menu")
    val shared2Dir = mockDir("shared2")
    val wiiDir = mockDir("Wii")
    every { tree.root.findFile("Wii") } returns wiiDir
    every { wiiDir.findFile("shared2") } returns shared2Dir
    every { shared2Dir.findFile("menu") } returns menuDir
    every { menuDir.findFile("FaceLib") } returns faceLibDir
    every { tree.faceLibDir } returns faceLibDir
    val faceLibFile = mockk<DocumentFile>(relaxed = true)
    val faceLibUri = mockk<Uri>(relaxed = true)
    every { faceLibFile.name } returns SaveManager.UserDataPaths.RFL_DB
    every { faceLibFile.uri } returns faceLibUri
    every { faceLibFile.exists() } returns includeFaceLib
    every { faceLibFile.isFile } returns includeFaceLib
    every { faceLibDir.findFile(SaveManager.UserDataPaths.RFL_DB) } returns faceLibFile
    every { faceLibDir.createFile(any(), any()) } returns faceLibFile
    every { faceLibFile.delete() } returns true
    every { resolver.openInputStream(faceLibUri) } returns
        ByteArrayInputStream("MII".encodeToByteArray())
    every { resolver.openOutputStream(faceLibUri) } returns ByteArrayOutputStream()

    // Pulsar + pul files + (optional) Ghosts
    val pulsarDir = mockDir("Pulsar")
    val pulsarRrDir = mockDir("RetroRewind6")
    every { shared2Dir.findFile("Pulsar") } returns pulsarDir
    every { pulsarDir.findFile("RetroRewind6") } returns pulsarRrDir
    every { tree.pulsarRrDir } returns pulsarRrDir
    val pulFiles = mutableMapOf<String, DocumentFile>()
    for (name in SaveManager.UserDataPaths.PUL_FILES) {
      val file = mockk<DocumentFile>(relaxed = true)
      val fileUri = mockk<Uri>(relaxed = true)
      every { file.name } returns name
      every { file.uri } returns fileUri
      every { file.exists() } returns includePul
      every { file.isFile } returns includePul
      every { pulsarRrDir.findFile(name) } returns file
      every { file.delete() } returns true
      every { resolver.openInputStream(fileUri) } returns
          ByteArrayInputStream(name.encodeToByteArray())
      every { resolver.openOutputStream(fileUri) } returns ByteArrayOutputStream()
      pulFiles[name] = file
    }
    // writeDolphinBytes replaces via createFile; return the mapped mock
    // so restore writes land on the stubbed output streams.
    every { pulsarRrDir.createFile(any(), any()) } answers
        {
          pulFiles[secondArg<String>()] ?: error("unexpected createFile: ${secondArg<String>()}")
        }

    var ghostsDir: DocumentFile? = null
    if (includeGhosts) {
      val g = mockk<DocumentFile>(relaxed = true)
      val ghost1 = mockk<DocumentFile>(relaxed = true)
      val ghost1Uri = mockk<Uri>(relaxed = true)
      every { g.name } returns SaveManager.UserDataPaths.GHOSTS_DIR
      every { g.exists() } returns true
      every { g.isDirectory } returns true
      every { g.listFiles() } returns arrayOf(ghost1)
      every { ghost1.name } returns "rkg1"
      every { ghost1.isFile } returns true
      every { ghost1.isDirectory } returns false
      every { ghost1.uri } returns ghost1Uri
      every { resolver.openInputStream(ghost1Uri) } returns
          ByteArrayInputStream("ghost".encodeToByteArray())
      every { resolver.openOutputStream(ghost1Uri) } returns ByteArrayOutputStream()
      every { pulsarRrDir.findFile(SaveManager.UserDataPaths.GHOSTS_DIR) } returns g
      ghostsDir = g
    } else {
      every { pulsarRrDir.findFile(SaveManager.UserDataPaths.GHOSTS_DIR) } returns null
    }

    // NAND (Wii/title/) — needed by findNandSaveFile and navigateOrCreate.
    // Stub createDirectory so restore tests can create NAND path dirs.
    val titleDir = mockDir("title")
    every { wiiDir.findFile("title") } returns titleDir
    every { wiiDir.createDirectory("title") } returns titleDir
    every { titleDir.createDirectory(any()) } answers { mockDir(firstArg()) }

    // Vanilla save NAND chain (includeVanillaSaves stubs RMCP for brevity).
    if (includeVanillaSaves) {
      val titleIdDir = mockDir(SaveManager.TITLE_ID)
      val regionDir = mockDir(Region.PAL.hexCode())
      val dataDir = mockDir("data")
      val saveFile = mockk<DocumentFile>(relaxed = true)
      val saveFileUri = mockk<Uri>(relaxed = true)
      every { saveFile.name } returns SaveManager.SAVE_FILE_NAME
      every { saveFile.uri } returns saveFileUri
      every { saveFile.exists() } returns true
      every { saveFile.isFile } returns true
      every { saveFile.delete() } returns true
      every { titleDir.findFile(SaveManager.TITLE_ID) } returns titleIdDir
      every { titleDir.createDirectory(SaveManager.TITLE_ID) } returns titleIdDir
      every { titleIdDir.findFile(Region.PAL.hexCode()) } returns regionDir
      every { titleIdDir.createDirectory(Region.PAL.hexCode()) } returns regionDir
      every { regionDir.findFile("data") } returns dataDir
      every { regionDir.createDirectory("data") } returns dataDir
      every { dataDir.findFile(SaveManager.SAVE_FILE_NAME) } returns saveFile
      every { dataDir.createFile(any(), any()) } returns saveFile
      every { resolver.openInputStream(saveFileUri) } returns
          ByteArrayInputStream("vanilla-RMCP".encodeToByteArray())
    }
    if (includePatchedIso) {
      val patchedIdDir = mockDir(SaveManager.TITLE_ID)
      val patchedRegionDir = mockDir(SaveManager.PATCHED_ISO_HEX_ID)
      val patchedDataDir = mockDir("data")
      val patchedSaveFile = mockk<DocumentFile>(relaxed = true)
      val patchedSaveFileUri = mockk<Uri>(relaxed = true)
      every { patchedSaveFile.name } returns SaveManager.SAVE_FILE_NAME
      every { patchedSaveFile.uri } returns patchedSaveFileUri
      every { patchedSaveFile.exists() } returns true
      every { patchedSaveFile.isFile } returns true
      every { patchedSaveFile.delete() } returns true
      every { titleDir.findFile(SaveManager.TITLE_ID) } returns patchedIdDir
      every { titleDir.createDirectory(SaveManager.TITLE_ID) } returns patchedIdDir
      every { patchedIdDir.findFile(SaveManager.PATCHED_ISO_HEX_ID) } returns patchedRegionDir
      every { patchedIdDir.createDirectory(SaveManager.PATCHED_ISO_HEX_ID) } returns
          patchedRegionDir
      every { patchedRegionDir.findFile("data") } returns patchedDataDir
      every { patchedRegionDir.createDirectory("data") } returns patchedDataDir
      every { patchedDataDir.findFile(SaveManager.SAVE_FILE_NAME) } returns patchedSaveFile
      every { patchedDataDir.createFile(any(), any()) } returns patchedSaveFile
      every { resolver.openInputStream(patchedSaveFileUri) } returns
          ByteArrayInputStream("patched-iso".encodeToByteArray())
    }

    return BackupEnv(
        tree,
        resolver,
        packDir,
        regionFiles,
        faceLibFile,
        pulFiles,
        ghostsDir,
        wiiDir,
        titleDir,
    )
  }

  /**
   * Builds a manifest.json for hand-rolled restore zips. Mirrors the production [SaveManager]
   * manifest shape; `rrVersion` is omitted from the JSON when null so tests can exercise both
   * variants.
   */
  private fun manifestJson(
      rrVersion: String?,
      retroWfc: List<String> = emptyList(),
      vanillaSaves: List<String> = emptyList(),
      patchedIso: Boolean = false,
      faceLib: Boolean = false,
      pulsar: List<String> = emptyList(),
      ghosts: Int = 0,
  ): JSONObject {
    val obj = JSONObject()
    obj.put("version", SaveManager.BACKUP_FORMAT_VERSION)
    obj.put("type", SaveManager.BACKUP_TYPE)
    obj.put("createdAt", System.currentTimeMillis())
    rrVersion?.let { obj.put("rrVersion", it) }
    obj.put(
        "contents",
        JSONObject().apply {
          put("retroWfc", org.json.JSONArray(retroWfc))
          put("vanillaSaves", org.json.JSONArray(vanillaSaves))
          put("patchedIso", patchedIso)
          put("faceLib", faceLib)
          put("pulsar", org.json.JSONArray(pulsar))
          put("ghosts", ghosts)
        },
    )
    return obj
  }

  /**
   * Builds a restore zip containing a manifest with [rrVersion] plus all three Pulsar pul file
   * entries (contents match the env's stubbed input streams), stubbing [env] for the [source] URI.
   */
  private fun sourceUriWith(source: Uri, env: BackupEnv, rrVersion: String?) {
    val backupBytes =
        ByteArrayOutputStream().use { baos ->
          java.util.zip.ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestJson(rrVersion = rrVersion).toString().encodeToByteArray())
            zos.closeEntry()
            for (name in SaveManager.UserDataPaths.PUL_FILES) {
              zos.putNextEntry(ZipEntry("Wii/shared2/Pulsar/RetroRewind6/$name"))
              zos.write(name.encodeToByteArray())
              zos.closeEntry()
            }
          }
          baos.toByteArray()
        }
    every { env.resolver.openInputStream(source) } returns ByteArrayInputStream(backupBytes)
  }

  private fun setupRestoreEnv(env: BackupEnv) {
    // The findFile chains wired up in setupBackupEnv already resolve
    // every restore target; RFL_DB restore writes a new file at the
    // same uri.
  }

  private data class ZipRead(val name: String, val bytes: ByteArray)

  private fun readZipEntries(bytes: ByteArray): List<ZipRead> {
    val out = mutableListOf<ZipRead>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.isDirectory) continue
        out.add(ZipRead(entry.name, zip.readBytes()))
      }
    }
    return out
  }
}
