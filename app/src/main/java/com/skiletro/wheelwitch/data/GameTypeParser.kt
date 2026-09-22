package com.skiletro.wheelwitch.data

import com.skiletro.wheelwitch.util.io.ByteReader
import java.io.File

object GameTypeParser {

  enum class GameFormat {
    Invalid,
    Iso,
    Rvz,
    Wbfs,
    Wad,
  }

  data class GameInfo(
      val format: GameFormat,
      val gameId: String?,
  )

  private val knownMarioKartWiiIds = setOf("RMCP01", "RMCE01", "RMCJ01", "RMCK01")

  fun checkValidity(pathname: String, bytes: ByteArray): Boolean {
    val extension = File(pathname).extension.uppercase()
    val info = parseGameInfoForExtension(extension, bytes)
    return info.format != GameFormat.Invalid
  }

  fun parseGameInfo(pathname: String, bytes: ByteArray): GameInfo {
    val extension = File(pathname).extension.uppercase()
    return parseGameInfoForExtension(extension, bytes)
  }

  private fun parseGameInfoForExtension(extension: String, bytes: ByteArray): GameInfo {
    return when (extension) {
      "ISO" -> checkIsValidIso(bytes)
      "RVZ" -> checkIsValidRvz(bytes)
      "WBFS" -> checkIsValidWbfs(bytes)
      "WAD" -> checkIsValidWad(bytes)
      else -> GameInfo(GameFormat.Invalid, null)
    }
  }

  private fun checkIsValidIso(bytes: ByteArray): GameInfo {
    val gameId = ByteReader.readASCII(bytes, 0, 6)
    val format = if (gameId in knownMarioKartWiiIds) GameFormat.Iso else GameFormat.Invalid
    return GameInfo(format, gameId)
  }

  private fun checkIsValidRvz(bytes: ByteArray): GameInfo {
    if (!ByteReader.checkMagic(bytes, 0, byteArrayOf(0x52, 0x56, 0x5A, 0x01))) {
      return GameInfo(GameFormat.Invalid, null)
    }
    val gameId = ByteReader.readASCII(bytes, 0x58, 6)
    val format = if (gameId in knownMarioKartWiiIds) GameFormat.Rvz else GameFormat.Invalid
    return GameInfo(format, gameId)
  }

  private fun checkIsValidWbfs(bytes: ByteArray): GameInfo {
    if (!ByteReader.checkMagic(bytes, 0, byteArrayOf(0x57, 0x42, 0x46, 0x53))) {
      return GameInfo(GameFormat.Invalid, null)
    }
    val hdSectorShift =
        bytes.getOrNull(8)?.toInt()?.and(0xFF) ?: return GameInfo(GameFormat.Invalid, null)
    val wbfsSectorShift =
        bytes.getOrNull(9)?.toInt()?.and(0xFF) ?: return GameInfo(GameFormat.Invalid, null)
    if (hdSectorShift !in 9..30 || wbfsSectorShift !in 0..30) {
      return GameInfo(GameFormat.Invalid, null)
    }
    val wlbaOffset = (1L shl hdSectorShift) + WBFS_WLBA_TABLE_OFFSET
    if (wlbaOffset + 2 > bytes.size) return GameInfo(GameFormat.Invalid, null)
    val firstWlbaEntry = ByteReader.readUInt16BE(bytes, wlbaOffset.toInt())
    val gameIdOffset = firstWlbaEntry.toLong() * (1L shl wbfsSectorShift)
    if (gameIdOffset + GAME_ID_LENGTH > bytes.size) return GameInfo(GameFormat.Invalid, null)
    val gameId = ByteReader.readASCII(bytes, gameIdOffset.toInt(), GAME_ID_LENGTH)
    val format = if (gameId in knownMarioKartWiiIds) GameFormat.Wbfs else GameFormat.Invalid
    return GameInfo(format, gameId)
  }

  private fun checkIsValidWad(bytes: ByteArray): GameInfo {
    val magic = byteArrayOf(0x00, 0x00, 0x00, 0x20)
    if (!ByteReader.checkMagic(bytes, 0, magic)) {
      return GameInfo(GameFormat.Invalid, null)
    }
    return GameInfo(GameFormat.Wad, null)
  }

  private const val WBFS_WLBA_TABLE_OFFSET = 256L
  private const val GAME_ID_LENGTH = 6
}
