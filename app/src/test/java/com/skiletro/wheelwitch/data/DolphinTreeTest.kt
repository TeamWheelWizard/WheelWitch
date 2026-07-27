package com.skiletro.wheelwitch.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.util.prefs.PrefsKeys
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class DolphinTreeTest {

  private lateinit var context: Context
  private lateinit var resolver: ContentResolver
  private lateinit var root: DocumentFile
  private lateinit var treeUri: Uri

  @BeforeEach
  fun setUp() {
    context = mockk(relaxed = true)
    resolver = mockk(relaxed = true)
    every { context.contentResolver } returns resolver
    root = mockk(relaxed = true)
    // Android's Uri stub returns null under unitTests.isReturnDefaultValues,
    // so we use a mock Uri and stub the static Uri.parse to return it.
    treeUri = mockk(relaxed = true)
    every { treeUri.toString() } returns "content://com.skiletro.wheelwitch.tree/tree/abc"
    mockkStatic(Uri::class)
    every { Uri.parse(any()) } returns treeUri

    mockkStatic(DocumentFile::class)
    every { DocumentFile.fromTreeUri(context, treeUri) } returns root
  }

  @AfterEach
  fun tearDown() {
    unmockkStatic(Uri::class)
    unmockkStatic(DocumentFile::class)
    unmockkStatic(DocumentsContract::class)
    unmockkStatic(com.skiletro.wheelwitch.util.prefs.Prefs::class)
  }

  // --- constructor & lazy subdirectories -------------------------------

  @Test
  fun `constructor error when DocumentFile fromTreeUri returns null`() {
    unmockkStatic(DocumentFile::class)
    mockkStatic(DocumentFile::class)
    every { DocumentFile.fromTreeUri(context, any()) } returns null
    val ex = assertThrows<IllegalStateException> { DolphinTree(context, treeUri) }
    assertThat(ex.message).contains("Invalid tree URI")
  }

  @Test
  fun `wheelWitchDir returns the existing WheelWitch path without creating`() {
    val wheelWitchDir = mockk<DocumentFile>(relaxed = true)
    every { root.findFile("WheelWitch") } returns wheelWitchDir
    every { wheelWitchDir.isDirectory } returns true

    val tree = DolphinTree(context, treeUri)
    assertThat(tree.wheelWitchDir).isEqualTo(wheelWitchDir)
    verify(exactly = 0) { root.createDirectory(any<String>()) }
  }

  @Test
  fun `wheelWitchDir creates the missing WheelWitch directory`() {
    val wheelWitchDir = mockk<DocumentFile>(relaxed = true)
    every { root.findFile("WheelWitch") } returns null
    every { root.createDirectory("WheelWitch") } returns wheelWitchDir

    val tree = DolphinTree(context, treeUri)
    assertThat(tree.wheelWitchDir).isEqualTo(wheelWitchDir)
    verify(exactly = 1) { root.createDirectory("WheelWitch") }
  }

  @Test
  fun `romDir creates the rom subdirectory under wheelWitchDir`() {
    val (romDir, _, _) =
      setupDirChain().let { Triple(it.first, it.second, it.third) }
    val tree = DolphinTree(context, treeUri)
    assertThat(tree.romDir).isEqualTo(romDir)
  }

  @Test
  fun `packDir creates the pack subdirectory under wheelWitchDir`() {
    val (_, packDir, _) = setupDirChain()
    val tree = DolphinTree(context, treeUri)
    assertThat(tree.packDir).isEqualTo(packDir)
  }

  // --- new lazy user-data subdirs (Wii/shared2/...) -------------------

  @Test
  fun `wiiDir is null when Wii is absent from the tree root`() {
    val tree = DolphinTree(context, treeUri)
    assertThat(tree.wiiDir).isNull()
  }

  @Test
  fun `wiiDir returns the existing Wii directory without creating`() {
    val wiiDir = mockk<DocumentFile>(relaxed = true)
    every { root.findFile("Wii") } returns wiiDir
    every { wiiDir.isDirectory } returns true

    val tree = DolphinTree(context, treeUri)
    assertThat(tree.wiiDir).isEqualTo(wiiDir)
    verify(exactly = 0) { root.createDirectory("Wii") }
  }

  @Test
  fun `shared2Dir is null when wiiDir is null`() {
    val tree = DolphinTree(context, treeUri)
    assertThat(tree.shared2Dir).isNull()
  }

  @Test
  fun `faceLibDir is null when the Wii tree is shallow`() {
    val tree = DolphinTree(context, treeUri)
    assertThat(tree.faceLibDir).isNull()
  }

  @Test
  fun `pulsarRrDir returns the existing RetroRewind6 directory under Wii shared2 Pulsar`() {
    val wii = mockk<DocumentFile>(relaxed = true)
    val shared2 = mockk<DocumentFile>(relaxed = true)
    val pulsar = mockk<DocumentFile>(relaxed = true)
    val rr = mockk<DocumentFile>(relaxed = true)
    every { root.findFile("Wii") } returns wii
    every { wii.isDirectory } returns true
    every { wii.findFile("shared2") } returns shared2
    every { shared2.isDirectory } returns true
    every { shared2.findFile("Pulsar") } returns pulsar
    every { pulsar.isDirectory } returns true
    every { pulsar.findFile(DolphinTree.RETRO_REWIND_DIR_NAME) } returns rr
    every { rr.isDirectory } returns true

    val tree = DolphinTree(context, treeUri)
    assertThat(tree.pulsarRrDir).isEqualTo(rr)
  }

  // --- top-level helpers (used by SaveManager) ------------------------

  @Test
  fun `navigateOrCreate walks the parts and returns the deepest directory`() {
    val a = mockDir("a")
    val b = mockDir("b")
    val c = mockDir("c")
    val start = mockk<DocumentFile>(relaxed = true)
    every { start.findFile("a") } returns a
    every { a.findFile("b") } returns b
    every { b.findFile("c") } returns c

    val result = navigateOrCreate(start, listOf("a", "b", "c"))
    assertThat(result).isEqualTo(c)
  }

  @Test
  fun `navigateOrCreate creates a missing intermediate directory`() {
    val a = mockDir("a")
    val b = mockk<DocumentFile>(relaxed = true)
    val start = mockk<DocumentFile>(relaxed = true)
    every { start.findFile("a") } returns a
    every { a.findFile("b") } returns null
    every { a.createDirectory("b") } returns b
    every { b.isDirectory } returns true

    val result = navigateOrCreate(start, listOf("a", "b"))
    assertThat(result).isEqualTo(b)
  }

  @Test
  fun `findDir returns null when the parent is null`() {
    assertThat(findDir(null, "Wii")).isNull()
  }

  @Test
  fun `findDir returns null when the named child is absent`() {
    val parent = mockk<DocumentFile>(relaxed = true)
    every { parent.findFile("Wii") } returns null
    assertThat(findDir(parent, "Wii")).isNull()
  }

  @Test
  fun `findDir returns null when the named child is a file not a directory`() {
    val parent = mockk<DocumentFile>(relaxed = true)
    val child = mockk<DocumentFile>(relaxed = true)
    every { parent.findFile("Wii") } returns child
    every { child.isDirectory } returns false
    assertThat(findDir(parent, "Wii")).isNull()
  }

  // --- validate --------------------------------------------------------

  @Test
  fun `validate succeeds for primary storage with the expected tree id and authority`() {
    // The @BeforeEach sets Uri.parse to return the shared `treeUri`
    // mock whose `authority` defaults to null. We stub it here to
    // simulate what a real primary-storage URI would carry.
    mockkStatic(DocumentsContract::class)
    every { DocumentsContract.getTreeDocumentId(any()) } returns DolphinPaths.expectedTreeId()
    every { treeUri.authority } returns "com.android.externalstorage.documents"
    val result = DolphinTree.validate(Uri.parse("content://x/tree/abc"))
    assertThat(result.isSuccess).isTrue()
  }

  @Test
  fun `validate succeeds for the Dolphin provider root URI`() {
    // The Dolphin app exposes its own SAF provider
    // (org.dolphinemu.dolphinemu.user). The picker surfaces it on
    // devices where Dolphin is installed; the document id is
    // "root/". This URI is a valid alternative to the primary-storage
    // form and does not require MANAGE_EXTERNAL_STORAGE.
    mockkStatic(DocumentsContract::class)
    every { DocumentsContract.getTreeDocumentId(any()) } returns "root/"
    every { treeUri.authority } returns "org.dolphinemu.dolphinemu.user"
    val result = DolphinTree.validate(Uri.parse("content://x/tree/abc"))
    assertThat(result.isSuccess).isTrue()
  }

  @Test
  fun `validate fails for a subfolder of the Dolphin provider`() {
    // A subfolder of the Dolphin provider (e.g. root/GameSettings)
    // is rejected; the WheelWitch subdirs must be created at the
    // top of Dolphin's user folder, not inside some intermediate
    // location.
    mockkStatic(DocumentsContract::class)
    every { DocumentsContract.getTreeDocumentId(any()) } returns "root/GameSettings"
    every { treeUri.authority } returns "org.dolphinemu.dolphinemu.user"
    val result = DolphinTree.validate(Uri.parse("content://x/tree/abc"))
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `validate fails for an unrelated tree id`() {
    mockkStatic(DocumentsContract::class)
    every { DocumentsContract.getTreeDocumentId(any()) } returns "primary:Documents"
    every { treeUri.authority } returns "com.android.externalstorage.documents"
    val result = DolphinTree.validate(Uri.parse("content://x/tree/abc"))
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `validate fails when the tree id matches but the authority is unknown`() {
    // Defense against authority confusion: a primary-storage-shaped
    // document id under a non-Dolphin authority (e.g. a different
    // app's SAF provider or a content:// tree) is rejected.
    mockkStatic(DocumentsContract::class)
    every { DocumentsContract.getTreeDocumentId(any()) } returns DolphinPaths.expectedTreeId()
    every { treeUri.authority } returns "com.example.otherapp"
    val result = DolphinTree.validate(Uri.parse("content://x/tree/abc"))
    assertThat(result.isFailure).isTrue()
  }

  @Test
  fun `validate wraps an exception from DocumentsContract as a failure`() {
    mockkStatic(DocumentsContract::class)
    every { DocumentsContract.getTreeDocumentId(any()) } throws
      IllegalArgumentException("not a tree uri")
    val result = DolphinTree.validate(Uri.parse("content://x/tree/abc"))
    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `validate error message hints at the parent for a subfolder of the Dolphin provider`() {
    // The user picked root/Dump from the Dolphin provider picker. The
    // base "wrong folder" message alone is unhelpful; the user has to
    // guess what to pick instead. The hint tells them the parent.
    mockkStatic(DocumentsContract::class)
    every { DocumentsContract.getTreeDocumentId(any()) } returns "root/Dump"
    every { treeUri.authority } returns "org.dolphinemu.dolphinemu.user"
    val result = DolphinTree.validate(Uri.parse("content://x/tree/abc"))
    assertThat(result.isFailure).isTrue()
    val msg = result.exceptionOrNull()!!.message!!
    assertThat(msg).contains("subfolder")
    assertThat(msg).contains("root/")
  }

  @Test
  fun `validate error message hints at the parent for a subfolder of the primary storage root`() {
    // Same hint, but for the primary external storage case: the user
    // picked the dolphin's files dir + a subdir instead of the files
    // dir itself.
    mockkStatic(DocumentsContract::class)
    val subfolderId = "${DolphinPaths.expectedTreeId()}/Dump"
    every { DocumentsContract.getTreeDocumentId(any()) } returns subfolderId
    every { treeUri.authority } returns "com.android.externalstorage.documents"
    val result = DolphinTree.validate(Uri.parse("content://x/tree/abc"))
    assertThat(result.isFailure).isTrue()
    val msg = result.exceptionOrNull()!!.message!!
    assertThat(msg).contains("subfolder")
    assertThat(msg).contains(DolphinPaths.expectedTreeId())
  }

  @Test
  fun `validate error message has no parent hint for an unrelated tree id`() {
    // When the picked tree is unrelated to both accepted roots, the
    // base "wrong folder" message is the best we can do. No parent
    // hint should be appended.
    mockkStatic(DocumentsContract::class)
    every { DocumentsContract.getTreeDocumentId(any()) } returns "primary:Documents"
    every { treeUri.authority } returns "com.android.externalstorage.documents"
    val result = DolphinTree.validate(Uri.parse("content://x/tree/abc"))
    val msg = result.exceptionOrNull()!!.message!!
    assertThat(msg).doesNotContain("subfolder")
  }

  // --- fromPersisted / persist ----------------------------------------

  @Test
  fun `fromPersisted returns null when no URI is persisted`() {
    val prefs = mockk<SharedPreferences>(relaxed = true)
    every { prefs.getString(PrefsKeys.WHEELWITCH_TREE_URI_KEY, null) } returns null
    mockPrefsMain(prefs)
    assertThat(DolphinTree.fromPersisted(context)).isNull()
  }

  @Test
  fun `fromPersisted returns the tree when the URI is valid`() {
    val prefs = mockk<SharedPreferences>(relaxed = true)
    every { prefs.getString(PrefsKeys.WHEELWITCH_TREE_URI_KEY, null) } returns
      treeUri.toString()
    mockPrefsMain(prefs)
    val tree = DolphinTree.fromPersisted(context)
    assertThat(tree).isNotNull()
    assertThat(tree!!.treeUri).isEqualTo(treeUri)
  }

  @Test
  fun `fromPersisted clears the pref and returns null when fromTreeUri returns null`() {
    // Override the setUp stub: this test wants fromTreeUri to fail.
    every { DocumentFile.fromTreeUri(context, any()) } returns null
    val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    val prefs = mockk<SharedPreferences>(relaxed = true)
    every { prefs.getString(PrefsKeys.WHEELWITCH_TREE_URI_KEY, null) } returns
      "content://stale/tree/uri"
    every { prefs.edit() } returns editor
    // Production code chains editor.remove(...).apply(); return the
    // editor from remove() so the .apply() call has a non-null target.
    every { editor.remove(any<String>()) } returns editor
    mockPrefsMain(prefs)

    assertThat(DolphinTree.fromPersisted(context)).isNull()
    verify { editor.remove(PrefsKeys.WHEELWITCH_TREE_URI_KEY) }
    verify { editor.apply() }
  }

  @Test
  fun `persist writes the tree URI to the shared pref`() {
    val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    val prefs = mockk<SharedPreferences>(relaxed = true)
    every { prefs.edit() } returns editor
    // editor.putString(...).apply() needs the putString result to be
    // non-null for the chained apply() to land.
    every { editor.putString(any<String>(), any<String>()) } returns editor
    mockPrefsMain(prefs)
    val tree = DolphinTree(context, treeUri)
    val expected = treeUri.toString()

    DolphinTree.persist(context, tree)

    verify { editor.putString(PrefsKeys.WHEELWITCH_TREE_URI_KEY, expected) }
    verify { editor.apply() }
  }

  // --- copyRomFromSource -----------------------------------------------

  @Test
  fun `copyRomFromSource copies bytes into romDir as gameId ext`() = runBlocking {
    val (romDir, _, _) = setupDirChain()
    val source = Uri.parse("content://picker/file/1")
    val sourceBytes = byteArrayOf(0x52, 0x4D, 0x43, 0x50, 0x30, 0x31) // "RMCP01"
    val output = ByteArrayOutputStream()
    val created = mockk<DocumentFile>(relaxed = true)
    every { created.uri } returns Uri.parse("content://tree/rom/RMCP01.iso")
    every { romDir.findFile("RMCP01.iso") } returns null
    every { romDir.createFile("application/octet-stream", "RMCP01.iso") } returns created
    every { resolver.openInputStream(source) } returns ByteArrayInputStream(sourceBytes)
    every { resolver.openOutputStream(created.uri) } returns output

    val tree = DolphinTree(context, treeUri)
    val target = tree.copyRomFromSource(source, "RMCP01", "iso")

    assertThat(target).isEqualTo(created)
    assertThat(output.toByteArray()).isEqualTo(sourceBytes)
  }

  @Test
  fun `copyRomFromSource deletes an existing file with the same name before creating`() = runBlocking {
    val (romDir, _, _) = setupDirChain()
    val source = Uri.parse("content://picker/file/1")
    val existing = mockk<DocumentFile>(relaxed = true)
    val created = mockk<DocumentFile>(relaxed = true)
    every { created.uri } returns Uri.parse("content://tree/rom/RMCP01.iso")
    every { romDir.findFile("RMCP01.iso") } returns existing
    every { romDir.createFile("application/octet-stream", "RMCP01.iso") } returns created
    every { resolver.openInputStream(source) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
    every { resolver.openOutputStream(created.uri) } returns ByteArrayOutputStream()

    val tree = DolphinTree(context, treeUri)
    tree.copyRomFromSource(source, "RMCP01", "iso")
    verify { existing.delete() }
  }

  // --- extractZipToPack ------------------------------------------------

  @Test
  fun `extractZipToPack writes each file entry into packDir`(@TempDir tempDir: Path) = runBlocking {
    val (_, packDir, _) = setupDirChain()
    val zip = File(tempDir.toFile(), "pack.zip")
    ZipOutputStream(zip.outputStream()).use { zos ->
      zos.putNextEntry(ZipEntry("file1.txt"))
      zos.write("hello".encodeToByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("file2.bin"))
      zos.write(byteArrayOf(0x01, 0x02, 0x03))
      zos.closeEntry()
    }

    val outputs = mutableMapOf<String, ByteArrayOutputStream>()
    setupPackEntryWrite(packDir, "file1.txt", outputs)
    setupPackEntryWrite(packDir, "file2.bin", outputs)

    val tree = DolphinTree(context, treeUri)
    val progress = mutableListOf<ExtractProgress>()
    tree.extractZipToPack(zip) { progress.add(it) }

    // Sequence: 1 PreparingFolders + 2 files × 2 callbacks (before/after).
    // filesDone walks 0, 0, 1, 1, 2; phase walks PF, W, W, W, W.
    assertThat(progress.map { it.filesDone }).containsExactly(0, 0, 1, 1, 2).inOrder()
    assertThat(progress.map { it.filesTotal }).containsExactly(2, 2, 2, 2, 2).inOrder()
    assertThat(progress.map { it.phase })
      .containsExactly(
        ExtractingPhase.PreparingFolders,
        ExtractingPhase.WritingFiles,
        ExtractingPhase.WritingFiles,
        ExtractingPhase.WritingFiles,
        ExtractingPhase.WritingFiles,
      )
      .inOrder()
    assertThat(outputs["file1.txt"]?.toString(Charsets.UTF_8)).isEqualTo("hello")
    assertThat(outputs["file2.bin"]?.toByteArray()?.toList())
      .isEqualTo(byteArrayOf(0x01, 0x02, 0x03).toList())
  }

  @Test
  fun `extractZipToPack creates nested subdirectories for entries with a path`(@TempDir tempDir: Path) =
    runBlocking {
      val (_, packDir, _) = setupDirChain()
      val zip = File(tempDir.toFile(), "pack.zip")
      ZipOutputStream(zip.outputStream()).use { zos ->
        zos.putNextEntry(ZipEntry("riivolution/RetroRewind6.xml"))
        zos.write("<xml/>".encodeToByteArray())
        zos.closeEntry()
      }
      val riivolutionDir = mockk<DocumentFile>(relaxed = true)
      every { packDir.findFile("riivolution") } returns null
      every { packDir.createDirectory("riivolution") } returns riivolutionDir
      val created = mockk<DocumentFile>(relaxed = true)
      every { created.uri } returns Uri.parse("content://tree/pack/riivolution/RetroRewind6.xml")
      every { riivolutionDir.createFile("application/octet-stream", "RetroRewind6.xml") } returns
        created
      val output = ByteArrayOutputStream()
      every { resolver.openOutputStream(created.uri) } returns output

      val tree = DolphinTree(context, treeUri)
      val progress = mutableListOf<ExtractProgress>()
      tree.extractZipToPack(zip) { progress.add(it) }

      verify { packDir.createDirectory("riivolution") }
      assertThat(output.toString(Charsets.UTF_8)).isEqualTo("<xml/>")
      // The directory pre-pass fires first.
      assertThat(progress.first().phase).isEqualTo(ExtractingPhase.PreparingFolders)
      assertThat(progress.first().currentFile).isNull()
      // The file write phase follows, with the live entry name.
      val writingEntries = progress.filter { it.phase == ExtractingPhase.WritingFiles }
      assertThat(writingEntries.map { it.currentFile })
        .containsExactly("riivolution/RetroRewind6.xml", "riivolution/RetroRewind6.xml")
        .inOrder()
    }

  @Test
  fun `extractZipToPack skips directory entries`(@TempDir tempDir: Path) = runBlocking {
    val (_, packDir, _) = setupDirChain()
    val zip = File(tempDir.toFile(), "pack.zip")
    ZipOutputStream(zip.outputStream()).use { zos ->
      // Directory entry followed by a file entry; directory should not
      // produce a DocumentFile.createFile call.
      zos.putNextEntry(ZipEntry("subdir/"))
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("subdir/inside.txt"))
      zos.write("data".encodeToByteArray())
      zos.closeEntry()
    }
    val subdirDir = mockk<DocumentFile>(relaxed = true)
    every { packDir.findFile("subdir") } returns null
    every { packDir.createDirectory("subdir") } returns subdirDir
    val created = mockk<DocumentFile>(relaxed = true)
    every { created.uri } returns Uri.parse("content://tree/pack/subdir/inside.txt")
    every { subdirDir.findFile("inside.txt") } returns null
    every { subdirDir.createFile("application/octet-stream", "inside.txt") } returns created
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(created.uri) } returns output

    val tree = DolphinTree(context, treeUri)
    val progress = mutableListOf<ExtractProgress>()
    tree.extractZipToPack(zip) { progress.add(it) }

    // Only one file entry → one pair of WritingFiles callbacks.
    val writingEntries = progress.filter { it.phase == ExtractingPhase.WritingFiles }
    assertThat(writingEntries.map { it.filesDone }).containsExactly(0, 1).inOrder()
    assertThat(writingEntries.first().currentFile).isEqualTo("subdir/inside.txt")
    // Only the file entry produces a write.
    verify(exactly = 0) { packDir.createFile(any<String>(), any<String>()) }
    verify(exactly = 1) { subdirDir.createFile("application/octet-stream", "inside.txt") }
  }

  @Test
  fun `extractZipToPack reports PreparingFolders then WritingFiles phases`(@TempDir tempDir: Path) =
    runBlocking {
      val (_, packDir, _) = setupDirChain()
      val zip = File(tempDir.toFile(), "pack.zip")
      ZipOutputStream(zip.outputStream()).use { zos ->
        zos.putNextEntry(ZipEntry("a/"))
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("a/b.txt"))
        zos.write("x".encodeToByteArray())
        zos.closeEntry()
      }
      val aDir = mockk<DocumentFile>(relaxed = true)
      every { packDir.findFile("a") } returns null
      every { packDir.createDirectory("a") } returns aDir
      val created = mockk<DocumentFile>(relaxed = true)
      every { created.uri } returns Uri.parse("content://tree/pack/a/b.txt")
      every { aDir.createFile("application/octet-stream", "b.txt") } returns created
      val output = ByteArrayOutputStream()
      every { resolver.openOutputStream(created.uri) } returns output

      val tree = DolphinTree(context, treeUri)
      val progress = mutableListOf<ExtractProgress>()
      tree.extractZipToPack(zip) { progress.add(it) }

      assertThat(progress.first().phase).isEqualTo(ExtractingPhase.PreparingFolders)
      assertThat(progress.first().filesTotal).isEqualTo(1)
      assertThat(progress.first().currentFile).isNull()
      // Every subsequent callback is in the writing phase.
      assertThat(progress.drop(1).map { it.phase })
        .containsExactly(ExtractingPhase.WritingFiles, ExtractingPhase.WritingFiles)
    }

  @Test
  fun `extractZipToPack creates every ancestor directory even when no file lives at intermediate levels`(
    @TempDir tempDir: Path
  ) = runBlocking {
    // File at a/b/c/leaf.txt; no files directly under a/ or a/b/ on
    // their own. The pre-pass must still create the full a -> a/b ->
    // a/b/c chain, which it didn't before the ancestor-set fix
    // (the direct-parent-only set missed "a" and "a/b" entirely).
    val (_, packDir, _) = setupDirChain()
    val zip = File(tempDir.toFile(), "pack.zip")
    ZipOutputStream(zip.outputStream()).use { zos ->
      zos.putNextEntry(ZipEntry("a/b/c/leaf.txt"))
      zos.write("deep".encodeToByteArray())
      zos.closeEntry()
    }
    val aDir = mockk<DocumentFile>(relaxed = true)
    val abDir = mockk<DocumentFile>(relaxed = true)
    val abcDir = mockk<DocumentFile>(relaxed = true)
    every { packDir.findFile("a") } returns null
    every { packDir.createDirectory("a") } returns aDir
    every { aDir.findFile("b") } returns null
    every { aDir.createDirectory("b") } returns abDir
    every { abDir.findFile("c") } returns null
    every { abDir.createDirectory("c") } returns abcDir
    val created = mockk<DocumentFile>(relaxed = true)
    every { created.uri } returns Uri.parse("content://tree/pack/a/b/c/leaf.txt")
    every { abcDir.createFile("application/octet-stream", "leaf.txt") } returns created
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(created.uri) } returns output

    val tree = DolphinTree(context, treeUri)
    tree.extractZipToPack(zip) { /* no-op */ }

    verify { packDir.createDirectory("a") }
    verify { aDir.createDirectory("b") }
    verify { abDir.createDirectory("c") }
    assertThat(output.toString(Charsets.UTF_8)).isEqualTo("deep")
  }

  @Test
  fun `extractZipToPack replaces a pre-existing file of the same name`(
    @TempDir tempDir: Path
  ) = runBlocking {
    val (_, packDir, _) = setupDirChain()
    val zip = File(tempDir.toFile(), "pack.zip")
    val fileName = "file1.txt"
    val fileContent = "hello"
    ZipOutputStream(zip.outputStream()).use { zos ->
      zos.putNextEntry(ZipEntry(fileName))
      zos.write(fileContent.encodeToByteArray())
      zos.closeEntry()
    }
    val existing = mockk<DocumentFile>(relaxed = true)
    val created = mockk<DocumentFile>(relaxed = true)
    val uri = mockk<Uri>(relaxed = true)
    every { created.uri } returns uri
    every { existing.name } returns fileName
    every { packDir.listFiles() } returns arrayOf(existing)
    every { packDir.findFile(fileName) } returns existing
    every { packDir.createFile("application/octet-stream", fileName) } returns created
    val baos = ByteArrayOutputStream()
    every { resolver.openOutputStream(uri) } returns baos

    val tree = DolphinTree(context, treeUri)
    tree.extractZipToPack(zip) { /* no-op */ }

    verify { existing.delete() }
    assertThat(baos.toString(Charsets.UTF_8)).isEqualTo(fileContent)
  }

  @Test
  fun `extractZipToPack preserves files under all user data paths`(
    @TempDir tempDir: Path
  ) = runBlocking {
    val (_, packDir, _) = setupDirChain()
    val zip = File(tempDir.toFile(), "pack.zip")
    ZipOutputStream(zip.outputStream()).use { zos ->
      // One entry per protected prefix. The carve-out should leave
      // each pre-existing file alone and not even try to create a
      // replacement (which would otherwise get suffixed with `.1`
      // on the next update).
      zos.putNextEntry(ZipEntry("riivolution/save/RetroWFC/PAL/rksys.dat"))
      zos.write("template".encodeToByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("Wii/shared2/menu/FaceLib/RFL_DB.dat"))
      zos.write("faces".encodeToByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("Wii/shared2/Pulsar/RetroRewind6/RRRating.pul"))
      zos.write("rating".encodeToByteArray())
      zos.closeEntry()
    }
    // Chain: pack/riivolution/save/RetroWFC/PAL/rksys.dat
    val riivolutionDir = mockk<DocumentFile>(relaxed = true)
    val saveDir = mockk<DocumentFile>(relaxed = true)
    val retroWfcDir = mockk<DocumentFile>(relaxed = true)
    val palDir = mockk<DocumentFile>(relaxed = true)
    every { packDir.findFile("riivolution") } returns null
    every { packDir.createDirectory("riivolution") } returns riivolutionDir
    every { riivolutionDir.findFile("save") } returns null
    every { riivolutionDir.createDirectory("save") } returns saveDir
    every { saveDir.findFile("RetroWFC") } returns null
    every { saveDir.createDirectory("RetroWFC") } returns retroWfcDir
    every { retroWfcDir.findFile("PAL") } returns null
    every { retroWfcDir.createDirectory("PAL") } returns palDir
    val existingSave = mockk<DocumentFile>(relaxed = true)
    every { palDir.findFile("rksys.dat") } returns existingSave
    every { palDir.createFile(any<String>(), any<String>()) } returns mockk(relaxed = true)

    // Chain: pack/Wii/shared2/menu/FaceLib/RFL_DB.dat
    val wiiDir = mockk<DocumentFile>(relaxed = true)
    val shared2Dir = mockk<DocumentFile>(relaxed = true)
    val menuDir = mockk<DocumentFile>(relaxed = true)
    val faceLibDir = mockk<DocumentFile>(relaxed = true)
    every { packDir.findFile("Wii") } returns null
    every { packDir.createDirectory("Wii") } returns wiiDir
    every { wiiDir.findFile("shared2") } returns null
    every { wiiDir.createDirectory("shared2") } returns shared2Dir
    every { shared2Dir.findFile("menu") } returns null
    every { shared2Dir.createDirectory("menu") } returns menuDir
    every { menuDir.findFile("FaceLib") } returns null
    every { menuDir.createDirectory("FaceLib") } returns faceLibDir
    val existingFaceLib = mockk<DocumentFile>(relaxed = true)
    every { faceLibDir.findFile("RFL_DB.dat") } returns existingFaceLib
    every { faceLibDir.createFile(any<String>(), any<String>()) } returns mockk(relaxed = true)

    // Chain: pack/Wii/shared2/Pulsar/RetroRewind6/RRRating.pul
    val pulsarDir = mockk<DocumentFile>(relaxed = true)
    val pulsarRrDir = mockk<DocumentFile>(relaxed = true)
    every { shared2Dir.findFile("Pulsar") } returns null
    every { shared2Dir.createDirectory("Pulsar") } returns pulsarDir
    every { pulsarDir.findFile(DolphinTree.RETRO_REWIND_DIR_NAME) } returns null
    every { pulsarDir.createDirectory(DolphinTree.RETRO_REWIND_DIR_NAME) } returns pulsarRrDir
    val existingPul = mockk<DocumentFile>(relaxed = true)
    every { pulsarRrDir.findFile("RRRating.pul") } returns existingPul
    every { pulsarRrDir.createFile(any<String>(), any<String>()) } returns mockk(relaxed = true)

    val tree = DolphinTree(context, treeUri)
    tree.extractZipToPack(zip) { /* no-op */ }

    // Every existing file is left alone and no replacement is
    // created. The carve-out covers all three user-data prefixes.
    verify(exactly = 0) { existingSave.delete() }
    verify(exactly = 0) { palDir.createFile(any<String>(), any<String>()) }
    verify(exactly = 0) { existingFaceLib.delete() }
    verify(exactly = 0) { faceLibDir.createFile(any<String>(), any<String>()) }
    verify(exactly = 0) { existingPul.delete() }
    verify(exactly = 0) { pulsarRrDir.createFile(any<String>(), any<String>()) }
  }

  @Test
  fun `extractZipToPack rejects entries with dot-dot path components`(
    @TempDir tempDir: Path
  ) = runBlocking {
    val (_, packDir, _) = setupDirChain()
    val zip = File(tempDir.toFile(), "pack.zip")
    ZipOutputStream(zip.outputStream()).use { zos ->
      zos.putNextEntry(ZipEntry("good.txt"))
      zos.write("ok".encodeToByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("../../escape.txt"))
      zos.write("bad".encodeToByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("pack/../../escape2.txt"))
      zos.write("bad2".encodeToByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("deep/../../../escape3.txt"))
      zos.write("bad3".encodeToByteArray())
      zos.closeEntry()
    }

    val outputs = mutableMapOf<String, ByteArrayOutputStream>()
    setupPackEntryWrite(packDir, "good.txt", outputs)

    val tree = DolphinTree(context, treeUri)
    tree.extractZipToPack(zip) { /* no-op */ }

    // Only the safe entry was written; traversal entries were filtered out.
    assertThat(outputs).containsKey("good.txt")
    assertThat(outputs).doesNotContainKey("escape.txt")
    assertThat(outputs).doesNotContainKey("escape2.txt")
    assertThat(outputs).doesNotContainKey("escape3.txt")
  }

  // --- writeLaunchJson / readLaunchJson --------------------------------

  @Test
  fun `writeLaunchJson writes content as rr_autostartfile json`() {
    val (romDir, _, _) = setupDirChain()
    val file = mockk<DocumentFile>(relaxed = true)
    every { file.uri } returns Uri.parse("content://tree/rr_autostartfile.json")
    every { romDir.findFile(DolphinTree.LAUNCH_JSON_NAME) } returns null
    every {
      romDir.createFile("application/json", DolphinTree.LAUNCH_JSON_NAME)
    } returns file
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(file.uri) } returns output

    val tree = DolphinTree(context, treeUri)
    val result = tree.writeLaunchJson("""{"base-file":"/x"}""")

    assertThat(result).isEqualTo(file)
    assertThat(output.toString(Charsets.UTF_8)).isEqualTo("""{"base-file":"/x"}""")
  }

  @Test
  fun `writeLaunchJson replaces an existing file with the same name`() {
    val (romDir, _, _) = setupDirChain()
    val existing = mockk<DocumentFile>(relaxed = true)
    val file = mockk<DocumentFile>(relaxed = true)
    every { file.uri } returns Uri.parse("content://tree/rr_autostartfile.json")
    every { romDir.findFile(DolphinTree.LAUNCH_JSON_NAME) } returns existing
    every {
      romDir.createFile("application/json", DolphinTree.LAUNCH_JSON_NAME)
    } returns file
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(file.uri) } returns output

    val tree = DolphinTree(context, treeUri)
    tree.writeLaunchJson("new")

    verify { existing.delete() }
  }

  @Test
  fun `readLaunchJson returns null when the file does not exist`() {
    val (romDir, _, _) = setupDirChain()
    every { romDir.findFile(DolphinTree.LAUNCH_JSON_NAME) } returns null

    val tree = DolphinTree(context, treeUri)
    assertThat(tree.readLaunchJson()).isNull()
  }

  @Test
  fun `readLaunchJson returns the file content as UTF-8`() {
    val (romDir, _, _) = setupDirChain()
    val file = mockk<DocumentFile>(relaxed = true)
    every { file.uri } returns Uri.parse("content://tree/rr_autostartfile.json")
    every { romDir.findFile(DolphinTree.LAUNCH_JSON_NAME) } returns file
    every { resolver.openInputStream(file.uri) } returns
      ByteArrayInputStream("payload".encodeToByteArray())

    val tree = DolphinTree(context, treeUri)
    assertThat(tree.readLaunchJson()).isEqualTo("payload")
  }

  // --- writeRrCover / writeRrMetadata ----------------------------------

  @Test
  fun `writeRrCover writes the cover png into romDir and replaces an existing one`() = runBlocking {
    val (romDir, _, _) = setupDirChain()
    val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    every { context.resources.openRawResource(R.raw.rr_autostartfile_cover) } returns
      ByteArrayInputStream(pngBytes)
    val existing = mockk<DocumentFile>(relaxed = true)
    every { romDir.findFile("rr_autostartfile.cover.png") } returns existing
    val file = mockk<DocumentFile>(relaxed = true)
    val uri = mockk<Uri>(relaxed = true)
    every { file.uri } returns uri
    every { romDir.createFile("image/png", "rr_autostartfile.cover.png") } returns file
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(uri) } returns output

    val tree = DolphinTree(context, treeUri)
    tree.writeRrCover()

    verify { existing.delete() }
    assertThat(output.toByteArray()).isEqualTo(pngBytes)
    verify(exactly = 0) { romDir.createFile("text/xml", any<String>()) }
  }

  @Test
  fun `writeRrMetadata replaces the version placeholder and writes the templated xml`() =
    runBlocking {
      val (romDir, _, _) = setupDirChain()
      val template =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<app version="1">
  <name>Retro Rewind</name>
  <version>{VERSION}</version>
</app>
""".trimIndent()
      every { context.resources.openRawResource(R.raw.rr_autostartfile) } returns
        ByteArrayInputStream(template.encodeToByteArray())
      val file = mockk<DocumentFile>(relaxed = true)
      val uri = mockk<Uri>(relaxed = true)
      every { file.uri } returns uri
      every { romDir.createFile("text/xml", DolphinTree.METADATA_XML_NAME) } returns file
      val output = ByteArrayOutputStream()
      every { resolver.openOutputStream(uri) } returns output

      val tree = DolphinTree(context, treeUri)
      tree.writeRrMetadata(com.skiletro.wheelwitch.model.SemVersion(6, 11, 6))

      val rendered = output.toString(Charsets.UTF_8)
      assertThat(rendered).contains("<version>6.11.6</version>")
      assertThat(rendered).doesNotContain("{VERSION}")
      // The rest of the template is preserved.
      assertThat(rendered).contains("<name>Retro Rewind</name>")
      assertThat(rendered).contains("<app version=\"1\">")
    }

  @Test
  fun `writeRrMetadata deletes an existing xml before creating a new one`() = runBlocking {
    val (romDir, _, _) = setupDirChain()
    every { context.resources.openRawResource(R.raw.rr_autostartfile) } returns
      ByteArrayInputStream("<x>{VERSION}</x>".encodeToByteArray())
    val existing = mockk<DocumentFile>(relaxed = true)
    every { romDir.findFile(DolphinTree.METADATA_XML_NAME) } returns existing
    val file = mockk<DocumentFile>(relaxed = true)
    val uri = mockk<Uri>(relaxed = true)
    every { file.uri } returns uri
    every { romDir.createFile("text/xml", DolphinTree.METADATA_XML_NAME) } returns file
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(uri) } returns output

    val tree = DolphinTree(context, treeUri)
    tree.writeRrMetadata(com.skiletro.wheelwitch.model.SemVersion(6, 11, 6))

    verify { existing.delete() }
    assertThat(output.toString(Charsets.UTF_8)).isEqualTo("<x>6.11.6</x>")
  }

  // --- readVersion / writeVersion -------------------------------------

  @Test
  fun `readVersion returns null when the version file is missing`() {
    val (_, packDir, _) = setupDirChain()
    val retroRewindDir = setupRetroRewindDir(packDir)
    every { retroRewindDir.findFile(DolphinTree.VERSION_FILE_NAME) } returns null

    val tree = DolphinTree(context, treeUri)
    assertThat(tree.readVersion()).isNull()
  }

  @Test
  fun `readVersion returns null when the file contents are not a valid semver`() {
    val (_, packDir, _) = setupDirChain()
    val retroRewindDir = setupRetroRewindDir(packDir)
    val file = mockk<DocumentFile>(relaxed = true)
    every { file.uri } returns Uri.parse("content://tree/pack/RetroRewind6/version.txt")
    every { retroRewindDir.findFile(DolphinTree.VERSION_FILE_NAME) } returns file
    every { resolver.openInputStream(file.uri) } returns
      ByteArrayInputStream("not-a-version".encodeToByteArray())

    val tree = DolphinTree(context, treeUri)
    assertThat(tree.readVersion()).isNull()
  }

  @Test
  fun `readVersion parses a valid semver from the pack dir`() {
    val (_, packDir, _) = setupDirChain()
    val retroRewindDir = setupRetroRewindDir(packDir)
    val file = mockk<DocumentFile>(relaxed = true)
    every { file.uri } returns Uri.parse("content://tree/pack/RetroRewind6/version.txt")
    every { retroRewindDir.findFile(DolphinTree.VERSION_FILE_NAME) } returns file
    every { resolver.openInputStream(file.uri) } returns
      ByteArrayInputStream("3.2.6\n".encodeToByteArray())

    val tree = DolphinTree(context, treeUri)
    val version = tree.readVersion()
    assertThat(version).isNotNull()
    assertThat(version!!.major).isEqualTo(3)
    assertThat(version.minor).isEqualTo(2)
    assertThat(version.patch).isEqualTo(6)
  }

  @Test
  fun `writeVersion replaces an existing version file and writes the toString form`() = runBlocking {
    val (_, packDir, _) = setupDirChain()
    val retroRewindDir = setupRetroRewindDir(packDir)
    val existing = mockk<DocumentFile>(relaxed = true)
    val file = mockk<DocumentFile>(relaxed = true)
    val fileUri = mockk<Uri>(relaxed = true)
    every { file.uri } returns fileUri
    every { retroRewindDir.findFile(DolphinTree.VERSION_FILE_NAME) } returns existing
    every {
      retroRewindDir.createFile("text/plain", DolphinTree.VERSION_FILE_NAME)
    } returns file
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(fileUri) } returns output

    val tree = DolphinTree(context, treeUri)
    tree.writeVersion(com.skiletro.wheelwitch.model.SemVersion(3, 2, 6))

    verify { existing.delete() }
    assertThat(output.toString(Charsets.UTF_8)).isEqualTo("3.2.6")
  }

  // --- persistUriPermission -------------------------------------------

  @Test
  fun `persistUriPermission takes read and write flags`() {
    val tree = DolphinTree(context, treeUri)
    tree.persistUriPermission()
    verify {
      resolver.takePersistableUriPermission(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
      )
    }
  }

  // --- readConfigIni / writeConfigIni ---------------------------------

  @Test
  fun `readConfigIni returns null when Config dir is missing`() {
    every { root.findFile("Config") } returns null
    every { root.createDirectory("Config") } returns null

    val tree = DolphinTree(context, treeUri)

    assertThat(tree.readConfigIni()).isNull()
  }

  @Test
  fun `readConfigIni returns null when Dolphin ini is missing under Config`() {
    val configDir = mockk<DocumentFile>(relaxed = true)
    every { root.findFile("Config") } returns configDir
    every { configDir.isDirectory } returns true
    every { configDir.findFile(DolphinTree.CONFIG_INI_NAME) } returns null

    val tree = DolphinTree(context, treeUri)

    assertThat(tree.readConfigIni()).isNull()
  }

  @Test
  fun `readConfigIni returns the file content as UTF-8`() {
    val configDir = mockk<DocumentFile>(relaxed = true)
    val file = mockk<DocumentFile>(relaxed = true)
    every { root.findFile("Config") } returns configDir
    every { configDir.isDirectory } returns true
    every { configDir.findFile(DolphinTree.CONFIG_INI_NAME) } returns file
    val fileUri = mockk<Uri>(relaxed = true)
    every { file.uri } returns fileUri
    every { resolver.openInputStream(fileUri) } returns
      ByteArrayInputStream("[General]\nISOPaths = 0\n".encodeToByteArray())

    val tree = DolphinTree(context, treeUri)

    assertThat(tree.readConfigIni()).isEqualTo("[General]\nISOPaths = 0\n")
  }

  @Test
  fun `writeConfigIni creates the Config dir and writes the file contents`() {
    val configDir = mockk<DocumentFile>(relaxed = true)
    val file = mockk<DocumentFile>(relaxed = true)
    val fileUri = mockk<Uri>(relaxed = true)
    every { file.uri } returns fileUri
    every { root.findFile("Config") } returns null
    every { root.createDirectory("Config") } returns configDir
    every { configDir.findFile(DolphinTree.CONFIG_INI_NAME) } returns null
    every {
      configDir.createFile("text/plain", DolphinTree.CONFIG_INI_NAME)
    } returns file
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(fileUri) } returns output

    val tree = DolphinTree(context, treeUri)
    val result = tree.writeConfigIni("[General]\nISOPaths = 1\nISOPath0 = /x\n")

    assertThat(result).isEqualTo(file)
    assertThat(output.toString(Charsets.UTF_8))
      .isEqualTo("[General]\nISOPaths = 1\nISOPath0 = /x\n")
  }

  @Test
  fun `writeConfigIni deletes a pre-existing ini before creating`() {
    val configDir = mockk<DocumentFile>(relaxed = true)
    val existing = mockk<DocumentFile>(relaxed = true)
    val file = mockk<DocumentFile>(relaxed = true)
    val fileUri = mockk<Uri>(relaxed = true)
    every { file.uri } returns fileUri
    every { root.findFile("Config") } returns configDir
    every { configDir.isDirectory } returns true
    every { configDir.findFile(DolphinTree.CONFIG_INI_NAME) } returns existing
    every {
      configDir.createFile("text/plain", DolphinTree.CONFIG_INI_NAME)
    } returns file
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(fileUri) } returns output

    val tree = DolphinTree(context, treeUri)
    tree.writeConfigIni("payload")

    verify { existing.delete() }
    assertThat(output.toString(Charsets.UTF_8)).isEqualTo("payload")
  }

  // --- readGameIni / writeGameIni -------------------------------------

  @Test
  fun `readGameIni returns null when file is absent`() {
    val gameSettingsDir = mockk<DocumentFile>(relaxed = true)
    every { root.findFile("GameSettings") } returns gameSettingsDir
    every { gameSettingsDir.isDirectory } returns true
    every { gameSettingsDir.findFile("RMC.ini") } returns null

    val tree = DolphinTree(context, treeUri)
    assertThat(tree.readGameIni("RMC")).isNull()
  }

  @Test
  fun `readGameIni returns content when file exists`() {
    val gameSettingsDir = mockk<DocumentFile>(relaxed = true)
    val file = mockk<DocumentFile>(relaxed = true)
    every { root.findFile("GameSettings") } returns gameSettingsDir
    every { gameSettingsDir.isDirectory } returns true
    every { gameSettingsDir.findFile("RMC.ini") } returns file
    every { resolver.openInputStream(file.uri) } returns
      ByteArrayInputStream("[Core]\nEnableCheats = False\n".encodeToByteArray())

    val tree = DolphinTree(context, treeUri)
    assertThat(tree.readGameIni("RMC")).isEqualTo("[Core]\nEnableCheats = False\n")
  }

  @Test
  fun `writeGameIni creates a new file in GameSettings`() {
    val gameSettingsDir = mockk<DocumentFile>(relaxed = true)
    val file = mockk<DocumentFile>(relaxed = true)
    val fileUri = mockk<Uri>(relaxed = true)
    every { root.findFile("GameSettings") } returns gameSettingsDir
    every { gameSettingsDir.isDirectory } returns true
    every { gameSettingsDir.findFile("RMC.ini") } returns null
    every { gameSettingsDir.createFile("text/plain", "RMC.ini") } returns file
    every { file.uri } returns fileUri
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(fileUri) } returns output

    val tree = DolphinTree(context, treeUri)
    tree.writeGameIni("RMC", "[Core]\nEnableCheats = False\n")

    assertThat(output.toString(Charsets.UTF_8)).isEqualTo("[Core]\nEnableCheats = False\n")
  }

  @Test
  fun `writeGameIni replaces an existing file with the same name`() {
    val gameSettingsDir = mockk<DocumentFile>(relaxed = true)
    val existing = mockk<DocumentFile>(relaxed = true)
    val file = mockk<DocumentFile>(relaxed = true)
    val fileUri = mockk<Uri>(relaxed = true)
    every { root.findFile("GameSettings") } returns gameSettingsDir
    every { gameSettingsDir.isDirectory } returns true
    every { gameSettingsDir.findFile("RMC.ini") } returns existing
    every { gameSettingsDir.createFile("text/plain", "RMC.ini") } returns file
    every { file.uri } returns fileUri
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(fileUri) } returns output

    val tree = DolphinTree(context, treeUri)
    tree.writeGameIni("RMC", "new content")

    verify { existing.delete() }
    assertThat(output.toString(Charsets.UTF_8)).isEqualTo("new content")
  }

  // --- ensureRmcGameInis ----------------------------------------------

  @Test
  fun `ensureRmcGameInis creates RMC ini from scratch when absent`() {
    val gameSettingsDir = setupGameSettingsDir()
    val configDir = setupConfigDir()
    every { configDir.findFile("RetroAchievements.ini") } returns null
    every { gameSettingsDir.findFile("RMC.ini") } returns null
    val file = mockk<DocumentFile>(relaxed = true)
    val fileUri = mockk<Uri>(relaxed = true)
    every { file.uri } returns fileUri
    every { gameSettingsDir.createFile("text/plain", "RMC.ini") } returns file
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(fileUri) } returns output

    val tree = DolphinTree(context, treeUri)
    tree.ensureRmcGameInis()

    val written = output.toString(Charsets.UTF_8)
    assertThat(written).contains("[Core]")
    assertThat(written).contains("EnableCheats = False")
    assertThat(written).contains("[Dolphin.Core]")
    assertThat(written).contains("[Achievements.Achievements]")
    assertThat(written).contains("Enabled = False")
  }

  @Test
  fun `ensureRmcGameInis preserves existing user settings in RMC ini`() {
    val gameSettingsDir = setupGameSettingsDir()
    val configDir = setupConfigDir()
    every { configDir.findFile("RetroAchievements.ini") } returns null
    val existingContent = "[Core]\nSomeCustomSetting = Value\n"
    val existingFile = mockk<DocumentFile>(relaxed = true)
    val file = mockk<DocumentFile>(relaxed = true)
    val fileUri = mockk<Uri>(relaxed = true)
    every { gameSettingsDir.findFile("RMC.ini") } returns existingFile
    every { resolver.openInputStream(existingFile.uri) } returns
      ByteArrayInputStream(existingContent.encodeToByteArray())
    every { gameSettingsDir.createFile("text/plain", "RMC.ini") } returns file
    every { file.uri } returns fileUri
    val output = ByteArrayOutputStream()
    every { resolver.openOutputStream(fileUri) } returns output

    val tree = DolphinTree(context, treeUri)
    tree.ensureRmcGameInis()

    val written = output.toString(Charsets.UTF_8)
    assertThat(written).contains("SomeCustomSetting = Value")
    assertThat(written).contains("EnableCheats = False")
  }

  @Test
  fun `ensureRmcGameInis strips DolphinCore cheats from sibling RMC ini files`() {
    val gameSettingsDir = setupGameSettingsDir()
    val configDir = setupConfigDir()
    every { configDir.findFile("RetroAchievements.ini") } returns null
    // RMC.ini already correct — no rewrite needed.
    val rmcFile = mockk<DocumentFile>(relaxed = true)
    val rmcUri = mockk<Uri>(relaxed = true)
    every { rmcFile.uri } returns rmcUri
    every { gameSettingsDir.findFile("RMC.ini") } returns rmcFile
    every { resolver.openInputStream(rmcUri) } returns
      ByteArrayInputStream(
        "[Core]\nEnableCheats = False\n[Dolphin.Core]\nEnableCheats = False\n[Achievements.Achievements]\nEnabled = False\n"
          .encodeToByteArray()
      )

    // RMCP01.ini has conflicting EnableCheats = True.
    val rmcp01File = mockk<DocumentFile>(relaxed = true)
    val rmcp01Uri = mockk<Uri>(relaxed = true)
    every { rmcp01File.name } returns "RMCP01.ini"
    every { rmcp01File.uri } returns rmcp01Uri
    every { resolver.openInputStream(rmcp01Uri) } returns
      ByteArrayInputStream(
        "[Core]\nSomeSetting = Value\nEnableCheats = True\n[Dolphin.Core]\nEnableCheats = True\n"
          .encodeToByteArray()
      )

    val cleanedFile = mockk<DocumentFile>(relaxed = true)
    val cleanedUri = mockk<Uri>(relaxed = true)
    every { cleanedFile.uri } returns cleanedUri
    every { gameSettingsDir.listFiles() } returns arrayOf(rmcFile, rmcp01File)
    every { gameSettingsDir.createFile("text/plain", "RMCP01.ini") } returns cleanedFile
    val cleanedOutput = ByteArrayOutputStream()
    every { resolver.openOutputStream(cleanedUri) } returns cleanedOutput

    val tree = DolphinTree(context, treeUri)
    tree.ensureRmcGameInis()

    verify { rmcp01File.delete() }
    val cleaned = cleanedOutput.toString(Charsets.UTF_8)
    assertThat(cleaned).doesNotContain("EnableCheats")
    assertThat(cleaned).contains("[Core]")
  }

  @Test
  fun `ensureRmcGameInis is idempotent`() {
    val gameSettingsDir = setupGameSettingsDir()
    val configDir = setupConfigDir()
    val retroConfigFile = mockk<DocumentFile>(relaxed = true)
    val retroConfigContents = """
[Achievements]
Enabled = False
"""
    every { configDir.findFile("RetroAchievements.ini") } returns retroConfigFile
    every { resolver.openInputStream(retroConfigFile.uri) } returns
            ByteArrayInputStream(retroConfigContents.encodeToByteArray())
    val correctContent = """
# Auto-generated by Wheel Witch. Do NOT touch any of the values below, they might get deleted.

[Core]
EnableCheats = False

[Dolphin.Core]
EnableCheats = False

[Achievements.Achievements]
Enabled = False
"""
    val file = mockk<DocumentFile>(relaxed = true)
    every { gameSettingsDir.findFile("RMC.ini") } returns file
    every { resolver.openInputStream(file.uri) } returns
            ByteArrayInputStream(correctContent.encodeToByteArray())
    every { gameSettingsDir.listFiles() } returns arrayOf(file)

    val tree = DolphinTree(context, treeUri)
    tree.ensureRmcGameInis()

    // No createFile or openOutputStream calls — content unchanged.
    verify(exactly = 0) { gameSettingsDir.createFile(any<String>(), any<String>()) }
    verify(exactly = 0) { resolver.openOutputStream(any()) }
  }

  // --- addIniKeyValueAtTheEnd (pure) ----------------------------------------

  @Test
  fun `addIniKeyValueAtTheEnd appends section and key when both absent`() {
    val result = addIniKeyValueAtTheEnd("", "[Core]", "EnableCheats", "False")
    assertThat(result).contains("[Core]")
    assertThat(result).contains("EnableCheats = False")
  }

  // --- removeIniKeyInSection (pure) ------------------------------------

  @Test
  fun `removeIniKeyInSection removes key from section`() {
    val input = "[Core]\nEnableCheats = True\nSomeOther = Value\n"
    val result = removeIniKeyInSection(input, "[Core]", "EnableCheats")
    assertThat(result).doesNotContain("EnableCheats")
    assertThat(result).contains("SomeOther = Value")
  }

  @Test
  fun `removeIniKeyInSection preserves other sections and keys`() {
    val input = "[Core]\nEnableCheats = True\nSomeOther = Value\n[Dolphin.Core]\nOtherKey = X\n"
    val result = removeIniKeyInSection(input, "[Core]", "EnableCheats")
    assertThat(result).contains("[Core]\nSomeOther = Value")
    assertThat(result).contains("[Dolphin.Core]")
    assertThat(result).contains("OtherKey = X")
  }

  @Test
  fun `removeIniKeyInSection returns content unchanged when section absent`() {
    val input = "[Other]\nKey = Value\n"
    val result = removeIniKeyInSection(input, "[Core]", "EnableCheats")
    assertThat(result).isEqualTo(input)
  }

  // --- helpers ---------------------------------------------------------

  /**
   * Stubs the lazy subdirectory chain so the full WheelWitch path
   * resolves to fresh [DocumentFile] mocks. Returns
   * `(romDir, packDir, wheelWitchDir)`.
   */
  private fun setupDirChain(): Triple<DocumentFile, DocumentFile, DocumentFile> {
    val wheelWitchDir = mockk<DocumentFile>(relaxed = true)
    val romDir = mockk<DocumentFile>(relaxed = true)
    val packDir = mockk<DocumentFile>(relaxed = true)
    every { root.findFile("WheelWitch") } returns wheelWitchDir
    every { wheelWitchDir.isDirectory } returns true
    every { wheelWitchDir.findFile("rom") } returns romDir
    every { wheelWitchDir.findFile("pack") } returns packDir
    every { romDir.isDirectory } returns true
    every { packDir.isDirectory } returns true
    return Triple(romDir, packDir, wheelWitchDir)
  }

  /**
   * Stubs the [DolphinTree.retroRewindDir] lazy so it resolves to a
   * fresh [DocumentFile] mock under the given [packDir]. Used by the
   * version-related tests.
   */
  private fun setupRetroRewindDir(packDir: DocumentFile): DocumentFile {
    val retroRewindDir = mockk<DocumentFile>(relaxed = true)
    every { packDir.findFile(DolphinTree.RETRO_REWIND_DIR_NAME) } returns retroRewindDir
    every { retroRewindDir.isDirectory } returns true
    return retroRewindDir
  }

  /** Stubs a top-level pack entry write: a file `<name>` is created and its bytes captured. */
  private fun setupPackEntryWrite(
    packDir: DocumentFile,
    name: String,
    outputs: MutableMap<String, ByteArrayOutputStream>,
  ) {
    val file = mockk<DocumentFile>(relaxed = true)
    // Use a unique mock Uri per file so the byte stream destinations
    // don't collapse; Uri.parse under unitTests.isReturnDefaultValues
    // returns null, so we can't rely on it.
    val uri = mockk<Uri>(relaxed = true)
    every { file.uri } returns uri
    every { packDir.createFile("application/octet-stream", name) } returns file
    val baos = ByteArrayOutputStream()
    outputs[name] = baos
    every { resolver.openOutputStream(uri) } returns baos
  }

  /** Stubs `Prefs.main(context)` to return [prefs] for tests that read or write prefs. */
  private fun mockPrefsMain(prefs: SharedPreferences) {
    mockkStatic("com.skiletro.wheelwitch.util.prefs.Prefs")
    io.mockk.every { com.skiletro.wheelwitch.util.prefs.Prefs.main(context) } returns prefs
  }

  /** Stubs the `Config/` directory under the tree root. */
  private fun setupConfigDir(): DocumentFile {
    val configDir = mockk<DocumentFile>(relaxed = true)
    every { findOrCreateDir(root, "Config") } returns configDir
    every { configDir.isDirectory } returns true
    return configDir
  }

  /** Stubs the `GameSettings/` directory under the tree root. */
  private fun setupGameSettingsDir(): DocumentFile {
    val gameSettingsDir = mockk<DocumentFile>(relaxed = true)
    every { root.findFile("GameSettings") } returns gameSettingsDir
    every { gameSettingsDir.isDirectory } returns true
    return gameSettingsDir
  }

  /** A DocumentFile mock with `isDirectory = true` so `navigateOrCreate` / `findDir` keep the chain. */
  private fun mockDir(name: String): DocumentFile {
    val dir = mockk<DocumentFile>(relaxed = true)
    every { dir.name } returns name
    every { dir.isDirectory } returns true
    return dir
  }
}
