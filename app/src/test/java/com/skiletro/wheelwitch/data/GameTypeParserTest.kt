package com.skiletro.wheelwitch.data

import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.data.GameTypeParser.GameFormat
import org.junit.jupiter.api.Test
import java.util.Locale

class GameTypeParserTest {

    companion object {
        private val KNOWN_IDS = listOf("RMCP01", "RMCE01", "RMCJ01")

        fun createIsoBuffer(gameId: String): ByteArray {
            val buf = ByteArray(6)
            gameId.encodeToByteArray().copyInto(buf, 0)
            return buf
        }

        fun createRvzBuffer(gameId: String): ByteArray {
            val buf = ByteArray(0x60)
            buf[0] = 0x52.toByte(); buf[1] = 0x56.toByte(); buf[2] = 0x5A.toByte(); buf[3] =
                0x01.toByte()
            gameId.encodeToByteArray().copyInto(buf, 0x58)
            return buf
        }

        fun createWbfsBuffer(
            gameId: String,
            hdSectorShift: Int = 9,
            wbfsSectorShift: Int = 2,
            wlbaEntry: Int = 512,
        ): ByteArray {
            val hdSectorSize = 1 shl hdSectorShift
            val wbfsSectorSize = 1 shl wbfsSectorShift
            val wlbaOffset = hdSectorSize + 256
            val dataOffset = wlbaEntry * wbfsSectorSize
            val totalSize = dataOffset + 6

            val buf = ByteArray(totalSize)
            buf[0] = 0x57.toByte(); buf[1] = 0x42.toByte(); buf[2] = 0x46.toByte(); buf[3] =
                0x53.toByte()
            buf[8] = hdSectorShift.toByte()
            buf[9] = wbfsSectorShift.toByte()
            buf[wlbaOffset] = (wlbaEntry shr 8).toByte()
            buf[wlbaOffset + 1] = wlbaEntry.toByte()
            gameId.encodeToByteArray().copyInto(buf, dataOffset)
            return buf
        }

        fun createWadBuffer(): ByteArray {
            return byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x42, 0x00, 0x00)
        }
    }

    // --- ISO ---

    @Test
    fun `parse ISO with valid game ID`() {
        val info = GameTypeParser.parseGameInfo("game.iso", createIsoBuffer("RMCP01"))
        assertThat(info.format).isEqualTo(GameFormat.Iso)
        assertThat(info.gameId).isEqualTo("RMCP01")
    }

    @Test
    fun `parse ISO with another valid game ID`() {
        val info = GameTypeParser.parseGameInfo("game.iso", createIsoBuffer("RMCE01"))
        assertThat(info.format).isEqualTo(GameFormat.Iso)
        assertThat(info.gameId).isEqualTo("RMCE01")
    }

    @Test
    fun `parse ISO with unknown game ID returns Invalid`() {
        val info = GameTypeParser.parseGameInfo("game.iso", createIsoBuffer("ABCDEF"))
        assertThat(info.format).isEqualTo(GameFormat.Invalid)
        assertThat(info.gameId).isEqualTo("ABCDEF")
    }

    @Test
    fun `parse ISO with short buffer returns Invalid`() {
        val info = GameTypeParser.parseGameInfo("game.iso", ByteArray(3))
        assertThat(info.format).isEqualTo(GameFormat.Invalid)
        assertThat(info.gameId).isEqualTo("")
    }

    // --- RVZ ---

    @Test
    fun `parse RVZ with valid magic and game ID`() {
        val info = GameTypeParser.parseGameInfo("game.rvz", createRvzBuffer("RMCP01"))
        assertThat(info.format).isEqualTo(GameFormat.Rvz)
        assertThat(info.gameId).isEqualTo("RMCP01")
    }

    @Test
    fun `parse RVZ with bad magic returns Invalid`() {
        val buf = createRvzBuffer("RMCP01")
        buf[0] = 0x58.toByte(); buf[1] = 0x58.toByte(); buf[2] = 0x58.toByte(); buf[3] =
            0x58.toByte()
        val info = GameTypeParser.parseGameInfo("game.rvz", buf)
        assertThat(info.format).isEqualTo(GameFormat.Invalid)
        assertThat(info.gameId).isNull()
    }

    @Test
    fun `parse RVZ with wrong game ID returns Invalid`() {
        val info = GameTypeParser.parseGameInfo("game.rvz", createRvzBuffer("ABCDEF"))
        assertThat(info.format).isEqualTo(GameFormat.Invalid)
        assertThat(info.gameId).isEqualTo("ABCDEF")
    }

    @Test
    fun `parse RVZ with short buffer returns Invalid`() {
        val info = GameTypeParser.parseGameInfo("game.rvz", ByteArray(3))
        assertThat(info.format).isEqualTo(GameFormat.Invalid)
        assertThat(info.gameId).isNull()
    }

    // --- WBFS ---

    @Test
    fun `parse WBFS with valid header returns Wbfs without game ID`() {
        val info = GameTypeParser.parseGameInfo("game.wbfs", createWbfsBuffer("RMCP01"))
        assertThat(info.format).isEqualTo(GameFormat.Wbfs)
        assertThat(info.gameId).isNull()
    }

    @Test
    fun `parse WBFS with bad magic returns Invalid`() {
        val buf = createWbfsBuffer("RMCP01")
        buf[0] = 0x00.toByte(); buf[1] = 0x00.toByte(); buf[2] = 0x00.toByte(); buf[3] =
            0x00.toByte()
        val info = GameTypeParser.parseGameInfo("game.wbfs", buf)
        assertThat(info.format).isEqualTo(GameFormat.Invalid)
        assertThat(info.gameId).isNull()
    }

    @Test
    fun `parse WBFS accepts any structurally valid header regardless of game ID`() {
        val info = GameTypeParser.parseGameInfo("game.wbfs", createWbfsBuffer("ABCDEF"))
        assertThat(info.format).isEqualTo(GameFormat.Wbfs)
        assertThat(info.gameId).isNull()
    }

    @Test
    fun `parse WBFS with small hdSectorShift returns Invalid`() {
        val buf = createWbfsBuffer("RMCP01", hdSectorShift = 8)
        val info = GameTypeParser.parseGameInfo("game.wbfs", buf)
        assertThat(info.format).isEqualTo(GameFormat.Invalid)
        assertThat(info.gameId).isNull()
    }

    @Test
    fun `parse WBFS when WLBA offset out of bounds returns Invalid`() {
        val buf = ByteArray(12)
        buf[0] = 0x57.toByte(); buf[1] = 0x42.toByte(); buf[2] = 0x46.toByte(); buf[3] =
            0x53.toByte()
        buf[8] = 14; buf[9] = 2
        val info = GameTypeParser.parseGameInfo("game.wbfs", buf)
        assertThat(info.format).isEqualTo(GameFormat.Invalid)
        assertThat(info.gameId).isNull()
    }

    @Test
    fun `parse WBFS when data offset out of bounds still returns Wbfs`() {
        val buf = ByteArray(770)
        buf[0] = 0x57.toByte(); buf[1] = 0x42.toByte(); buf[2] = 0x46.toByte(); buf[3] =
            0x53.toByte()
        buf[8] = 9; buf[9] = 2
        buf[768] = (1000 shr 8).toByte(); buf[769] = 1000.toByte()
        val info = GameTypeParser.parseGameInfo("game.wbfs", buf)
        assertThat(info.format).isEqualTo(GameFormat.Wbfs)
        assertThat(info.gameId).isNull()
    }

    @Test
    fun `parse WBFS with realistic layout (data offset beyond 4 KiB) returns Wbfs`() {
        val buf = createWbfsBuffer("RMCP01", hdSectorShift = 9, wbfsSectorShift = 15, wlbaEntry = 18)
        val info = GameTypeParser.parseGameInfo("game.wbfs", buf)
        assertThat(info.format).isEqualTo(GameFormat.Wbfs)
        assertThat(info.gameId).isNull()
    }

    // --- WAD ---

    @Test
    fun `parse WAD with valid magic`() {
        val info = GameTypeParser.parseGameInfo("file.wad", createWadBuffer())
        assertThat(info.format).isEqualTo(GameFormat.Wad)
        assertThat(info.gameId).isNull()
    }

    @Test
    fun `parse WAD with bad magic returns Invalid`() {
        val buf = createWadBuffer()
        buf[0] = 0x00.toByte(); buf[1] = 0x00.toByte(); buf[2] = 0x00.toByte(); buf[3] =
            0x21.toByte()
        val info = GameTypeParser.parseGameInfo("file.wad", buf)
        assertThat(info.format).isEqualTo(GameFormat.Invalid)
        assertThat(info.gameId).isNull()
    }

    @Test
    fun `parse WAD with short buffer returns Invalid`() {
        val info = GameTypeParser.parseGameInfo("file.wad", byteArrayOf(0x00, 0x00, 0x00))
        assertThat(info.format).isEqualTo(GameFormat.Invalid)
        assertThat(info.gameId).isNull()
    }

    // --- checkValidity ---

    @Test
    fun `lowercase extension remains valid under Turkish locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))

            assertThat(GameTypeParser.checkValidity("game.iso", createIsoBuffer("RMCP01")))
                .isTrue()
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `checkValidity returns true for valid ISO`() {
        assertThat(GameTypeParser.checkValidity("game.iso", createIsoBuffer("RMCP01"))).isTrue()
    }

    @Test
    fun `checkValidity returns false for invalid format`() {
        assertThat(GameTypeParser.checkValidity("game.txt", createIsoBuffer("RMCP01"))).isFalse()
    }

    @Test
    fun `checkValidity returns true for valid WAD`() {
        assertThat(GameTypeParser.checkValidity("file.wad", createWadBuffer())).isTrue()
    }

    @Test
    fun `checkValidity returns true for valid WBFS header`() {
        assertThat(GameTypeParser.checkValidity("game.wbfs", createWbfsBuffer("RMCP01"))).isTrue()
    }

    @Test
    fun `checkValidity returns false for bad WAD`() {
        assertThat(
            GameTypeParser.checkValidity(
                "file.wad",
                byteArrayOf(0x00, 0x00, 0x00, 0x21)
            )
        ).isFalse()
    }
}
