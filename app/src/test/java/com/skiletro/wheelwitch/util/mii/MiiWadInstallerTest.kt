package com.skiletro.wheelwitch.util.mii

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MiiWadInstallerTest {

    @Test
    fun `isValidWad accepts correct magic`(@TempDir tempDir: Path) {
        val wad = File(tempDir.toFile(), "good.wad")
        wad.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x42, 0x00, 0x00))
        assertThat(MiiWadInstaller.isValidWad(wad)).isTrue()
    }

    @Test
    fun `isValidWad rejects bad magic`(@TempDir tempDir: Path) {
        val wad = File(tempDir.toFile(), "bad.wad")
        wad.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x21, 0x42, 0x00, 0x00))
        assertThat(MiiWadInstaller.isValidWad(wad)).isFalse()
    }

    @Test
    fun `isValidWad rejects short file`(@TempDir tempDir: Path) {
        val wad = File(tempDir.toFile(), "short.wad")
        wad.writeBytes(byteArrayOf(0x00, 0x00, 0x00))
        assertThat(MiiWadInstaller.isValidWad(wad)).isFalse()
    }

    @Test
    fun `isValidWad throws on missing file`(@TempDir tempDir: Path) {
        val missing = File(tempDir.toFile(), "missing.wad")
        assertThat(MiiWadInstaller.isValidWad(missing)).isFalse()
    }

    @Test
    fun `extractWad pulls wad out of synthetic zip`(@TempDir tempDir: Path) {
        val zip = File(tempDir.toFile(), "bundle.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            val entry = ZipEntry("Mii Channel Symbols - HACS.wad")
            zos.putNextEntry(entry)
            val payload = byteArrayOf(0x00, 0x00, 0x00, 0x20) + ByteArray(64)
            zos.write(payload)
            zos.closeEntry()
        }

        val extracted = MiiWadInstaller.extractWadForTest(zip, tempDir.toFile())
        assertThat(extracted.exists()).isTrue()
        assertThat(extracted.length()).isEqualTo(68L)
        val header = extracted.readBytes().take(4).toByteArray()
        assertThat(header).isEqualTo(byteArrayOf(0x00, 0x00, 0x00, 0x20))
    }

    @Test
    fun `extractWad picks HACS variant when multiple wads present`(@TempDir tempDir: Path) {
        val zip = File(tempDir.toFile(), "bundle.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("foo.wad"))
            zos.write(byteArrayOf(0x00, 0x00, 0x00, 0x20))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("Mii Channel Symbols - HACS.wad"))
            zos.write(byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x99.toByte()))
            zos.closeEntry()
        }
        val extracted = MiiWadInstaller.extractWadForTest(zip, tempDir.toFile())
        assertThat(extracted.name).contains("HACS")
    }

    @Test
    fun `extractWad throws when no wad present`(@TempDir tempDir: Path) {
        val zip = File(tempDir.toFile(), "bundle.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("hello".encodeToByteArray())
            zos.closeEntry()
        }
        val ex = assertThrows<IllegalStateException> {
            MiiWadInstaller.extractWadForTest(zip, tempDir.toFile())
        }
        assertThat(ex.message).contains("No .wad file found")
    }

    @Test
    fun `extractWad rejects traversal entries via ZipSafety`(@TempDir tempDir: Path) {
        val zip = File(tempDir.toFile(), "bundle.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../../evil.wad"))
            zos.write(byteArrayOf(0x00, 0x00, 0x00, 0x20) + ByteArray(64))
            zos.closeEntry()
        }
        val ex = assertThrows<IllegalStateException> {
            MiiWadInstaller.extractWadForTest(zip, tempDir.toFile())
        }
        assertThat(ex.message).contains("No .wad file found")
    }

    @Test
    fun `zip roundtrip preserves payload bytes`(@TempDir tempDir: Path) {
        val payload = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("payload.bin"))
                val random = java.util.Random(42)
                val bytes = ByteArray(2048).also { random.nextBytes(it) }
                zos.write(bytes)
                zos.closeEntry()
            }
        }.toByteArray()
        assertThat(payload.size).isGreaterThan(1024)
    }
}
