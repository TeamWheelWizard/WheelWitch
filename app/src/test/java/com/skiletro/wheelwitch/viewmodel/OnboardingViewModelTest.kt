package com.skiletro.wheelwitch.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.R
import com.skiletro.wheelwitch.data.DolphinTree
import com.skiletro.wheelwitch.data.InvalidTreeReason
import com.skiletro.wheelwitch.data.InvalidTreeUriException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
  private val app: Application = mockk(relaxed = true)
  private val resolver: ContentResolver = mockk(relaxed = true)
  private val storageUri: Uri = mockk(relaxed = true)
  private val romUri: Uri = mockk(relaxed = true)
  private val root: DocumentFile = mockk(relaxed = true)
  private val wheelWitchDir: DocumentFile = mockk(relaxed = true)
  private val romDir: DocumentFile = mockk(relaxed = true)
  private val pickedRom: DocumentFile = mockk(relaxed = true)
  private val tree: DolphinTree = mockk(relaxed = true)
  private var installed = true
  private var persistedTree: DolphinTree? = tree
  private lateinit var vm: OnboardingViewModel

  @BeforeEach
  fun setUp() {
    val dispatcher = UnconfinedTestDispatcher()
    Dispatchers.setMain(dispatcher)
    mockkObject(DolphinTree.Companion)
    mockkStatic(DocumentFile::class)
    mockkStatic(DocumentsContract::class)
    every { app.contentResolver } returns resolver
    every { app.getString(R.string.onboarding_iso_invalid) } returns "Invalid ROM"
    every { app.getString(R.string.onboarding_storage_required) } returns "Select storage first"
    every { app.getString(R.string.onboarding_iso_copy_failed) } returns "Copy failed"
    every { app.getString(R.string.onboarding_metadata_write_failed) } returns "Cover failed"
    every { app.getString(R.string.onboarding_storage_subfolder_internal) } returns
        "Select the root"
    every { DolphinTree.validate(storageUri) } returns Result.success(Unit)
    every { DolphinTree.persist(app, any()) } returns Unit
    every { DocumentFile.fromTreeUri(app, storageUri) } returns root
    every { root.findFile("WheelWitch") } returns wheelWitchDir
    every { wheelWitchDir.isDirectory } returns true
    every { DocumentFile.fromSingleUri(app, romUri) } returns pickedRom
    every { pickedRom.name } returns "Mario Kart.ISO"
    every { resolver.openInputStream(romUri) } answers { ByteArrayInputStream(isoHeader("RMCE01")) }
    every { tree.romDir } returns romDir
    every { tree.resolver } returns resolver
    every { romDir.listFiles() } returns emptyArray()
    vm =
        OnboardingViewModel(
            application = app,
            treeFactory = { context ->
              assertThat(context).isSameInstanceAs(app)
              persistedTree
            },
            dolphinCheck = { context ->
              assertThat(context).isSameInstanceAs(app)
              installed
            },
            ioDispatcher = dispatcher,
        )
  }

  @AfterEach
  fun tearDown() {
    unmockkObject(DolphinTree.Companion)
    unmockkStatic(DocumentFile::class)
    unmockkStatic(DocumentsContract::class)
    Dispatchers.resetMain()
  }

  @Test
  fun `Dolphin is checked on entry and must be installed to continue`() = runTest {
    installed = false
    vm.next()

    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Dolphin)
    assertThat(vm.state.value.hasCheckedDolphin).isTrue()
    assertThat(vm.state.value.dolphinInstalled).isFalse()
    vm.next()
    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Dolphin)

    installed = true
    vm.checkDolphin()
    vm.next()
    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Storage)
    vm.back()
    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Dolphin)
  }

  @Test
  fun `tree deep link follows the checked Dolphin installation`() = runTest {
    val dolphinLink: Uri = mockk()
    val externalLink: Uri = mockk()
    every {
      DocumentsContract.buildTreeDocumentUri("org.dolphinemu.dolphinemu.user", "root")
    } returns dolphinLink
    every {
      DocumentsContract.buildTreeDocumentUri(
          "primary",
          "Android/data/org.dolphinemu.dolphinemu/files",
      )
    } returns externalLink

    installed = false
    vm.next()
    assertThat(vm.treeDeepLink).isSameInstanceAs(externalLink)
    installed = true
    vm.checkDolphin()
    assertThat(vm.treeDeepLink).isSameInstanceAs(dolphinLink)
  }

  @Test
  fun `cancelled storage picker stays put and invalid subfolder uses translated error`() = runTest {
    enterStorage()
    vm.selectStorage(null)
    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Storage)
    assertThat(vm.state.value.storageError).isNull()
    every { DolphinTree.validate(storageUri) } returns
        Result.failure(InvalidTreeUriException(InvalidTreeReason.SubfolderInternal, "raw message"))

    vm.selectStorage(storageUri)

    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Storage)
    assertThat(vm.state.value.storageError).isEqualTo("Select the root")
    verify(exactly = 0) { DolphinTree.persist(app, any()) }
    vm.back()
    assertThat(vm.state.value.storageError).isNull()
  }

  @Test
  fun `storage persists SAF permission and bootstraps before scanning valid existing ROM`() =
      runTest {
        val existing: DocumentFile = mockk(relaxed = true)
        val existingUri: Uri = mockk()
        every { existing.name } returns "RMCJ01.iso"
        every { existing.uri } returns existingUri
        every { existing.isFile } returns true
        every { existing.exists() } returns true
        every { romDir.listFiles() } returns arrayOf(existing)
        every { resolver.openInputStream(existingUri) } answers
            {
              ByteArrayInputStream(isoHeader("RMCJ01"))
            }

        enterRom()

        verify {
          resolver.takePersistableUriPermission(
              storageUri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )
          DolphinTree.persist(app, any())
          root.findFile("WheelWitch")
        }
        assertThat(vm.state.value.rom.existingRomName).isEqualTo("RMCJ01.iso")
        assertThat(vm.state.value.rom.activity).isEqualTo(OnboardingRomActivity.Idle)
        vm.confirmExistingRom()
        coVerify(exactly = 1) { tree.writeRrCover() }
        coVerify(exactly = 0) { tree.copyRomFromSource(any(), any(), any()) }
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Complete)
      }

  @Test
  fun `picked ROM is validated copied using its region then covered`() = runTest {
    enterRom()
    vm.selectRom(romUri)

    coVerify(exactly = 1) { tree.copyRomFromSource(romUri, "RMCE01", "iso") }
    coVerify(exactly = 1) { tree.writeRrCover() }
    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Complete)
    assertThat(vm.state.value.romError).isNull()
  }

  @Test
  fun `relaunching setup after completion resets the wizard and rechecks Dolphin`() = runTest {
    enterRom()
    vm.selectRom(romUri)
    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Complete)
    installed = false

    vm.restart()

    assertThat(vm.state.value).isEqualTo(OnboardingState())
    vm.next()
    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Dolphin)
    assertThat(vm.state.value.hasCheckedDolphin).isTrue()
    assertThat(vm.state.value.dolphinInstalled).isFalse()
  }

  @Test
  fun `invalid header stays on ROM step without copying`() = runTest {
    enterRom()
    every { resolver.openInputStream(romUri) } answers { ByteArrayInputStream(isoHeader("NOTMKW")) }

    vm.selectRom(romUri)

    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Rom)
    assertThat(vm.state.value.romError).isEqualTo("Invalid ROM")
    assertThat(vm.state.value.rom.activity).isEqualTo(OnboardingRomActivity.Idle)
    coVerify(exactly = 0) { tree.copyRomFromSource(any(), any(), any()) }
    coVerify(exactly = 0) { tree.writeRrCover() }
  }

  @Test
  fun `copy failure resets activity and does not attempt cover`() = runTest {
    enterRom()
    coEvery { tree.copyRomFromSource(any(), any(), any()) } throws IOException()

    vm.selectRom(romUri)

    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Rom)
    assertThat(vm.state.value.romError).isEqualTo("Copy failed")
    assertThat(vm.state.value.rom.activity).isEqualTo(OnboardingRomActivity.Idle)
    coVerify(exactly = 0) { tree.writeRrCover() }
  }

  @Test
  fun `back cancels copy and late provider completion cannot advance a newer ROM step`() = runTest {
    val release = CompletableDeferred<Unit>()
    var copyWasCancelled = false
    enterRom()
    coEvery { tree.copyRomFromSource(any(), any(), any()) } coAnswers
        {
          withContext(NonCancellable) { release.await() }
          copyWasCancelled = !currentCoroutineContext().isActive
          pickedRom
        }

    vm.selectRom(romUri)
    assertThat(vm.state.value.rom.activity).isEqualTo(OnboardingRomActivity.Copying)
    vm.back()
    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Storage)
    vm.selectStorage(storageUri)
    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Rom)
    release.complete(Unit)

    assertThat(copyWasCancelled).isTrue()
    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Rom)
    assertThat(vm.state.value.rom.activity).isEqualTo(OnboardingRomActivity.Idle)
    assertThat(vm.state.value.romError).isNull()
    coVerify(exactly = 0) { tree.writeRrCover() }
  }

  @Test
  fun `cover cancellation is not translated into an error or completion`() = runTest {
    enterRom()
    coEvery { tree.writeRrCover() } throws CancellationException("cancelled")

    vm.selectRom(romUri)

    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Rom)
    assertThat(vm.state.value.rom.activity).isEqualTo(OnboardingRomActivity.Idle)
    assertThat(vm.state.value.romError).isNull()
  }

  @Test
  fun `existing ROM confirmation requires storage to still be available`() = runTest {
    val existing: DocumentFile = mockk(relaxed = true)
    every { existing.name } returns "RMCP01.iso"
    every { existing.isFile } returns true
    every { existing.exists() } returns true
    every { resolver.openInputStream(existing.uri) } answers
        {
          ByteArrayInputStream(isoHeader("RMCP01"))
        }
    every { romDir.listFiles() } returns arrayOf(existing)
    enterRom()
    persistedTree = null

    vm.confirmExistingRom()

    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Rom)
    assertThat(vm.state.value.romError).isEqualTo("Select storage first")
    assertThat(vm.state.value.rom.activity).isEqualTo(OnboardingRomActivity.Idle)
    coVerify(exactly = 0) { tree.writeRrCover() }
  }

  @Test
  fun `scan cancellation resets activity without a user-facing error`() = runTest {
    every { romDir.listFiles() } throws CancellationException("cancelled")

    enterRom()

    assertThat(vm.state.value.rom.activity).isEqualTo(OnboardingRomActivity.Idle)
    assertThat(vm.state.value.romError).isNull()
    assertThat(vm.state.value.rom.existingRomName).isNull()
  }

  private fun enterStorage() {
    vm.next()
    vm.next()
  }

  private fun enterRom() {
    enterStorage()
    vm.selectStorage(storageUri)
    assertThat(vm.state.value.step).isEqualTo(OnboardingStep.Rom)
  }

  private fun isoHeader(gameId: String): ByteArray =
      ByteArray(4096).also { gameId.toByteArray(Charsets.US_ASCII).copyInto(it) }
}
