package com.skiletro.wheelwitch.util.io

import java.io.InputStream

/** Reads at most [maxBytes], handling streams that return short or zero-length reads. */
fun InputStream.readUpTo(maxBytes: Int): ByteArray {
  require(maxBytes >= 0) { "maxBytes must not be negative" }
  if (maxBytes == 0) return ByteArray(0)

  val bytes = ByteArray(maxBytes)
  var offset = 0
  while (offset < maxBytes) {
    val count = read(bytes, offset, maxBytes - offset)
    when {
      count < 0 -> break
      count > 0 -> offset += count
      else -> {
        val one = read()
        if (one < 0) break
        bytes[offset++] = one.toByte()
      }
    }
  }
  return if (offset == maxBytes) bytes else bytes.copyOf(offset)
}
