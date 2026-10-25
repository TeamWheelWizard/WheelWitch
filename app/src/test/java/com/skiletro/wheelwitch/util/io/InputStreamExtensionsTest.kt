package com.skiletro.wheelwitch.util.io

import com.google.common.truth.Truth.assertThat
import java.io.InputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InputStreamExtensionsTest {

  @Test
  fun `readUpTo keeps reading when stream returns one byte at a time`() {
    val input = DribbleInputStream(byteArrayOf(1, 2, 3, 4))

    assertThat(input.readUpTo(4)).isEqualTo(byteArrayOf(1, 2, 3, 4))
  }

  @Test
  fun `readUpTo returns bytes available before EOF`() {
    val input = DribbleInputStream(byteArrayOf(1, 2))

    assertThat(input.readUpTo(4)).isEqualTo(byteArrayOf(1, 2))
  }

  @Test
  fun `readUpTo handles a zero-length read without spinning`() {
    val input = ZeroThenDataInputStream(byteArrayOf(7, 8))

    assertThat(input.readUpTo(2)).isEqualTo(byteArrayOf(7, 8))
  }

  @Test
  fun `readUpTo rejects negative size`() {
    assertThrows<IllegalArgumentException> { DribbleInputStream(byteArrayOf()).readUpTo(-1) }
  }

  private open class DribbleInputStream(private val data: ByteArray) : InputStream() {
    private var position = 0

    override fun read(): Int = if (position == data.size) -1 else data[position++].toInt()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (position == data.size) return -1
      buffer[offset] = data[position++]
      return 1
    }
  }

  private class ZeroThenDataInputStream(data: ByteArray) : DribbleInputStream(data) {
    private var returnedZero = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (!returnedZero) {
        returnedZero = true
        return 0
      }
      return super.read(buffer, offset, length)
    }
  }
}
