package com.skiletro.wheelwitch.model

/**
 * A player's raw licence statistics, fed into
 * [com.skiletro.wheelwitch.domain.computeScore] to produce a [ScoreResult].
 */
data class LicenseStats(
  val vrPoints: Double,
  val vsWins: Int,
  val vsLosses: Int,
  val firsts: Int,
  val dist: Double,
  val dist1st: Double,
)

/** The computed score, rank, and normalised stat blend for a licence. */
data class ScoreResult(
  val score: Double,
  val rank: Int,
  val vrNorm: Double,
  val winPct: Double,
  val firstsNorm: Double,
  val distNorm: Double,
  val dist1stNorm: Double,
  val totalVs: Int,
  val meetsRaceReq: Boolean,
)

/** How far one stat is from the next-rank requirement. */
data class StatNeed(
  val neededNorm: Double,
  val neededRaw: Double,
  val feasibility: String,
  val extraWins: Int? = null,
)

/** Per-stat requirements for reaching the next rank. */
data class NextRankInfo(
  val threshold: Double?,
  val vr: StatNeed,
  val winPct: StatNeed,
  val firsts: StatNeed,
  val dist: StatNeed,
  val dist1st: StatNeed,
  val isMaxRank: Boolean = false,
)