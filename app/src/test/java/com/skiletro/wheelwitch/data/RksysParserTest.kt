package com.skiletro.wheelwitch.data

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.jupiter.api.Test

class RksysParserTest {

  companion object {
    private const val MII_NAME_OFFSET = 0x14
    private const val MII_NAME_LENGTH = 20
    private const val PID_OFFSET = 0x5C
    private const val RACE_WINS_OFFSET = 0x98
    private const val RACE_LOSSES_OFFSET = 0x9C
    private const val VR_OFFSET = 0xB0
    private const val MII_RFL_OFFSET = 0x5684
    private const val MII_RFL_DATA_LENGTH = 74

    /** The default size accommodates all four license slots. */
    fun createBuffer(size: Int = 0x20000) = ByteArray(size)

    fun ByteArray.writeAscii(offset: Int, text: String) {
      val bytes = text.encodeToByteArray()
      bytes.copyInto(this, offset)
    }

    /** Writes a signed 32-bit big-endian int at a given offset. */
    fun ByteArray.writeInt32BE(offset: Int, value: Int) {
      this[offset] = (value shr 24).toByte()
      this[offset + 1] = (value shr 16).toByte()
      this[offset + 2] = (value shr 8).toByte()
      this[offset + 3] = value.toByte()
    }

    /** Writes an unsigned 16-bit big-endian int at a given offset. */
    fun ByteArray.writeUInt16BE(offset: Int, value: Int) {
      this[offset] = (value shr 8).toByte()
      this[offset + 1] = value.toByte()
    }

    /** Writes an unsigned 32-bit big-endian long at a given offset. */
    fun ByteArray.writeUInt32BE(offset: Int, value: Long) {
      this[offset] = (value shr 24).toByte()
      this[offset + 1] = (value shr 16).toByte()
      this[offset + 2] = (value shr 8).toByte()
      this[offset + 3] = value.toByte()
    }

    /** Writes UTF-16BE code units without adding a terminator. */
    fun ByteArray.writeUTF16BE(offset: Int, text: String) {
      for ((i, c) in text.withIndex()) {
        val pos = offset + i * 2
        this[pos] = (c.code shr 8).toByte()
        this[pos + 1] = c.code.toByte()
      }
    }

    fun ByteArray.writeBytes(offset: Int, data: ByteArray) {
      data.copyInto(this, offset)
    }
  }

  @Test
  fun `parse empty or too-small buffer returns all licenses with exists false`() {
    val info = RksysParser.parse(ByteArray(0))
    assertThat(info.licenses).hasSize(4)
    info.licenses.forEach { assertThat(it.exists).isFalse() }
  }

  @Test
  fun `parse buffer without RKPD magic returns exists false`() {
    val buf = createBuffer()
    val info = RksysParser.parse(buf)
    info.licenses.forEach { assertThat(it.exists).isFalse() }
  }

  @Test
  fun `parse single valid license slot`() {
    val buf = createBuffer()
    val base = RksysParser.LICENSE_BASES[0]

    buf.writeAscii(base, "RKPD")
    buf.writeUTF16BE(base + MII_NAME_OFFSET, "TestName")
    buf.writeUInt32BE(base + PID_OFFSET, 12345678L)
    buf.writeUInt16BE(base + VR_OFFSET, 5000)
    buf.writeInt32BE(base + RACE_WINS_OFFSET, 100)
    buf.writeInt32BE(base + RACE_LOSSES_OFFSET, 50)

    val rflData = ByteArray(MII_RFL_DATA_LENGTH) { (it + 1).toByte() }
    buf.writeBytes(base + MII_RFL_OFFSET, rflData)

    val info = RksysParser.parse(buf)
    val license = info.licenses[0]
    assertThat(license.exists).isTrue()
    assertThat(license.slotIndex).isEqualTo(0)
    assertThat(license.miiName).isEqualTo("TestName")
    assertThat(license.vr).isEqualTo(5000)
    assertThat(license.raceWins).isEqualTo(100)
    assertThat(license.raceLosses).isEqualTo(50)
    assertThat(license.miiDataBase64).isEqualTo(Base64.getEncoder().encodeToString(rflData))
  }

  @Test
  fun `parse multiple valid license slots`() {
    val buf = createBuffer()

    for ((slotIndex, base) in RksysParser.LICENSE_BASES.withIndex()) {
      buf.writeAscii(base, "RKPD")
      buf.writeUTF16BE(base + MII_NAME_OFFSET, "Slot$slotIndex")
      buf.writeUInt32BE(base + PID_OFFSET, (slotIndex + 1) * 1000000L)
      buf.writeUInt16BE(base + VR_OFFSET, 1000 + slotIndex * 100)
    }

    val info = RksysParser.parse(buf)
    assertThat(info.licenses).hasSize(4)

    for (i in 0 until 4) {
      val license = info.licenses[i]
      assertThat(license.exists).isTrue()
      assertThat(license.slotIndex).isEqualTo(i)
      assertThat(license.miiName).isEqualTo("Slot$i")
      assertThat(license.vr).isEqualTo(1000 + i * 100)
    }
  }

  @Test
  fun `parse mixed slots with some missing RKPD`() {
    val buf = createBuffer()
    buf.writeAscii(RksysParser.LICENSE_BASES[0], "RKPD")
    buf.writeUTF16BE(RksysParser.LICENSE_BASES[0] + MII_NAME_OFFSET, "First")
    buf.writeAscii(RksysParser.LICENSE_BASES[2], "RKPD")
    buf.writeUTF16BE(RksysParser.LICENSE_BASES[2] + MII_NAME_OFFSET, "Third")

    val info = RksysParser.parse(buf)
    assertThat(info.licenses[0].exists).isTrue()
    assertThat(info.licenses[0].miiName).isEqualTo("First")
    assertThat(info.licenses[1].exists).isFalse()
    assertThat(info.licenses[2].exists).isTrue()
    assertThat(info.licenses[2].miiName).isEqualTo("Third")
    assertThat(info.licenses[3].exists).isFalse()
  }

  @Test
  fun `parse license with short RFL data returns null miiDataBase64`() {
    val buf = ByteArray(RksysParser.LICENSE_BASES[0] + MII_RFL_OFFSET + 10) // only 10 bytes of RFL data
    buf.writeAscii(RksysParser.LICENSE_BASES[0], "RKPD")

    val info = RksysParser.parse(buf)
    assertThat(info.licenses[0].exists).isTrue()
    assertThat(info.licenses[0].miiDataBase64).isNull()
  }

  @Test
  fun `parse license null friend code from PID 0`() {
    val buf = createBuffer()
    val base = RksysParser.LICENSE_BASES[0]
    buf.writeAscii(base, "RKPD")
    // PID = 0 means that the game does not show a friend code
    buf.writeUInt32BE(base + PID_OFFSET, 0L)

    val info = RksysParser.parse(buf)
    val friendCode = info.licenses[0].friendCode
    assertThat(friendCode).isNull()
  }

  @Test
  fun `parse license correct friend code from PID`() {
    val buf = createBuffer()
    val base = RksysParser.LICENSE_BASES[0]
    buf.writeAscii(base, "RKPD")
    buf.writeUInt32BE(base + PID_OFFSET, 1L)

    val info = RksysParser.parse(buf)
    val friendCode = info.licenses[0].friendCode
    assertThat(friendCode).matches("\\d{4}-\\d{4}-\\d{4}")
  }

  @Test
  fun `friend code is deterministic for known PID 0x12345678`() {
    val buf = createBuffer()
    val base = RksysParser.LICENSE_BASES[0]
    buf.writeAscii(base, "RKPD")
    buf.writeUInt32BE(base + PID_OFFSET, 0x12345678L)

    val info = RksysParser.parse(buf)
    assertThat(info.licenses[0].friendCode).isEqualTo("1892-8398-0920")
  }

  @Test
  fun `parse license UTF-16BE name with null terminator`() {
    val buf = createBuffer()
    val base = RksysParser.LICENSE_BASES[0]
    buf.writeAscii(base, "RKPD")
    buf.writeUTF16BE(base + MII_NAME_OFFSET, "A")
    // Leave null (2 bytes of zero) after "A"
    buf.writeUTF16BE(base + MII_NAME_OFFSET + 4, "B")

    val info = RksysParser.parse(buf)
    assertThat(info.licenses[0].miiName).isEqualTo("A")
  }
}
