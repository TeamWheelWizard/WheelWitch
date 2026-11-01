package com.skiletro.wheelwitch.model

import androidx.compose.runtime.Immutable

/**
 * Parsed `rksys.dat` save file. Always contains exactly 4 license
 * slots; the parser marks each as `exists = true/false` based on the
 * RKPD magic at the slot base.
 */
@Immutable
data class SaveFileInfo(val licenses: List<LicenseInfo>) {
  init {
    require(licenses.size == 4) {
      "SaveFileInfo must have 4 license slots, got ${licenses.size}"
    }
  }
}

/**
 * One of 4 license slots in a `rksys.dat` file. `exists` is false
 * when the slot has no RKPD magic; all other fields are null in that
 * case. `miiDataBase64` is the 74-byte RFL Mii payload encoded as
 * base64. The two `leaderboard*` fields are populated asynchronously
 * by [com.skiletro.wheelwitch.viewmodel.SaveDataViewModel] after the
 * local save is parsed.
 */
@Immutable
data class LicenseInfo(
  val slotIndex: Int,
  val exists: Boolean,
  val miiName: String? = null,
  val friendCode: String? = null,
  val profileId: Long? = null,
  val vr: Int? = null,
  val raceWins: Int? = null,
  val raceLosses: Int? = null,
  val ratingVr: Int? = null,
  val miiDataBase64: String? = null,
  val leaderboardVr: Int? = null,
  val firsts: Int? = null,
  val totalDist: Double? = null,
  val dist1st: Double? = null,
)
