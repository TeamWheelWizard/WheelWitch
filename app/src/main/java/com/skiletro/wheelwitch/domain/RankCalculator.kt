package com.skiletro.wheelwitch.domain

import com.skiletro.wheelwitch.model.LicenseStats
import com.skiletro.wheelwitch.model.NextRankInfo
import com.skiletro.wheelwitch.model.ScoreResult
import com.skiletro.wheelwitch.model.StatNeed
import kotlin.math.roundToLong

private const val W_VR = 0.60
private const val W_WIN = 0.15
private const val W_FIRSTS = 0.15
private const val W_DIST = 0.05
private const val W_DIST1ST = 0.05

private const val AH_VR = 100.0
private const val AH_WIN = 55.0
private const val AH_FIRSTS = 100.0
private const val AH_DIST = 100.0
private const val AH_DIST1ST = 100.0

private const val AL_VR = 5.0
private const val AL_WIN = 50.0
private const val AL_FIRSTS = 0.0
private const val AL_DIST = 0.0
private const val AL_DIST1ST = 0.0

private val M1 = W_VR * AH_VR + W_WIN * AH_WIN + W_FIRSTS * AH_FIRSTS +
  W_DIST * AH_DIST + W_DIST1ST * AH_DIST1ST
private val M2 = W_VR * AL_VR + W_WIN * AL_WIN + W_FIRSTS * AL_FIRSTS +
  W_DIST * AL_DIST + W_DIST1ST * AL_DIST1ST
private val ALPHA = 90.0 / (M1 - M2)
private val BETA = 100.0 - ALPHA * M1

private const val MIN_VS_MATCHES = 100
private const val MAX_VR_FANCY = 1_000_000.0
private const val MAX_VR_FLOAT = 10_000.0

/** Each rank's minimum score, indexed by rank-1. The final entry caps the "Crown" rank. */
val RANK_THRESH: List<Double> = listOf(0.0, 24.0, 36.0, 48.0, 60.0, 72.0, 84.0, 94.0, 100.0)

/** Display names for ranks 1..9, aligned with [RANK_THRESH]. */
val RANK_NAMES: List<String> = listOf("E", "D", "C", "B", "A", "1star", "2star", "3star", "Crown")

/**
 * Computes a player's score, normalised stats, and rank from their
 * licence statistics. The score is the weighted blend of the five
 * normalised stats scaled into `0..100`; it is only non-zero once the
 * player has raced at least [MIN_VS_MATCHES] matches.
 */
fun computeScore(stats: LicenseStats): ScoreResult {
  val totalVs = stats.vsWins + stats.vsLosses
  val winPct = if (totalVs > 0) 100.0 * stats.vsWins / totalVs else 45.0

  val vrInternal = toInternalVr(stats.vrPoints)
  val vrClamped = clamp(vrInternal, 0.0, 1000.0)
  val vrNorm = (vrClamped / 1000.0) * 100.0

  val firstsNorm = if (stats.firsts >= 2250) 100.0 else 100.0 * stats.firsts / 2250.0
  val distNorm = if (stats.dist >= 40000) 100.0 else 100.0 * stats.dist / 40000.0
  val dist1stNorm = if (stats.dist1st >= 10000) 100.0 else 100.0 * stats.dist1st / 10000.0

  val M = weightedScore(vrNorm, winPct, firstsNorm, distNorm, dist1stNorm)

  val meetsRaceReq = totalVs >= MIN_VS_MATCHES
  val score = if (meetsRaceReq) clamp(ALPHA * M + BETA, 0.0, 100.0) else 0.0
  val rank = if (meetsRaceReq) rankFromScore(score) else 0

  return ScoreResult(score, rank, vrNorm, winPct, firstsNorm, distNorm, dist1stNorm, totalVs, meetsRaceReq)
}

/** Maps a raw score to its rank band (1..9), derived from [RANK_THRESH]. */
fun rankFromScore(score: Double): Int = RANK_THRESH.count { score >= it }

/** The score needed to reach [rank], or null for the max rank. */
fun nextThreshold(rank: Int): Double? = RANK_THRESH.getOrNull(rank)

/** Recomputes the blended score from a [ScoreResult]'s already-normalised stats. */
fun wouldBeScore(result: ScoreResult): Double =
    (ALPHA * weightedScore(result.vrNorm, result.winPct, result.firstsNorm, result.distNorm, result.dist1stNorm) + BETA)
      .coerceIn(0.0, 100.0)

/** The rank [wouldBeScore] would award. */
fun wouldBeRank(result: ScoreResult): Int = rankFromScore(wouldBeScore(result))

/**
 * Computes how far each stat is from the next rank's threshold, given
 * the player's current [stats]. Returns [NextRankInfo.isMaxRank] for a
 * Crown-ranked player, whose per-stat needs are all zero.
 */
fun computeNeeds(stats: LicenseStats): NextRankInfo {
  val cur = computeScore(stats)
  val thr = nextThreshold(cur.rank)
    ?: return NextRankInfo(
      threshold = null,
      isMaxRank = true,
      vr = StatNeed(0.0, 0.0, ""),
      winPct = StatNeed(0.0, 0.0, ""),
      firsts = StatNeed(0.0, 0.0, ""),
      dist = StatNeed(0.0, 0.0, ""),
      dist1st = StatNeed(0.0, 0.0, ""),
    )

  val mReq = (thr - BETA) / ALPHA

  fun otherSum(omit: StatVal): Double =
      StatVal.entries.filter { it != omit }.sumOf { it.weight * it.norm(cur) }

  fun solveNorm(omit: StatVal): Double = (mReq - otherSum(omit)) / omit.weight

  fun feas(normReq: Double) =
      when {
        normReq <= 100.0 -> "ok"
        normReq <= 110.0 -> "warn"
        else -> "infeasible"
      }

  val vr = StatVal.VR
  val vrNormReq = solveNorm(vr)

  val win = StatVal.WIN
  val winPctNormReq = solveNorm(win)
  val winPctRaw = win.rawOf(winPctNormReq)
  val totalVs = stats.vsWins + stats.vsLosses
  val extraWins =
      if (totalVs > 0 && winPctRaw > cur.winPct) {
        val p = winPctRaw / 100.0
        if (p >= 1.0) {
          null
        } else {
          val x = Math.ceil((p * totalVs - stats.vsWins) / (1 - p)).toInt()
          if (x > 0) x else 0
        }
      } else {
        null
      }

  val firsts = StatVal.FIRSTS
  val firstsNormReq = solveNorm(firsts)
  val dist = StatVal.DIST
  val distNormReq = solveNorm(dist)
  val dist1st = StatVal.DIST1ST
  val dist1stNormReq = solveNorm(dist1st)

  return NextRankInfo(
    threshold = thr,
    vr = StatNeed(vrNormReq, vr.rawOf(vrNormReq), feas(vrNormReq)),
    winPct = StatNeed(winPctNormReq, winPctRaw, feas(winPctNormReq), extraWins),
    firsts = StatNeed(firstsNormReq, firsts.rawOf(firstsNormReq), feas(firstsNormReq)),
    dist = StatNeed(distNormReq, dist.rawOf(distNormReq), feas(distNormReq)),
    dist1st = StatNeed(dist1stNormReq, dist1st.rawOf(dist1stNormReq), feas(dist1stNormReq)),
  )
}

/** One of the five blend components, with its weight and normalisation rules. */
private enum class StatVal(
  val weight: Double,
  val norm: (ScoreResult) -> Double,
  val rawOf: (Double) -> Double,
) {
  VR(
    W_VR,
    { it.vrNorm },
    { (clamp(Math.ceil(it * 10.0), 0.0, 1000.0) * 100.0).roundToLong().toDouble() },
  ),
  WIN(W_WIN, { it.winPct }, { clamp(Math.ceil(it * 100) / 100, 0.0, 100.0) }),
  FIRSTS(
    W_FIRSTS,
    { it.firstsNorm },
    { clamp(Math.ceil(it * 22.5), 0.0, 2250.0).roundToLong().toDouble() },
  ),
  DIST(W_DIST, { it.distNorm }, { clamp(Math.ceil(it * 400.0), 0.0, 40000.0).roundToLong().toDouble() }),
  DIST1ST(W_DIST1ST, { it.dist1stNorm }, { clamp(Math.ceil(it * 100.0), 0.0, 10000.0).roundToLong().toDouble() }),
}

private fun clamp(v: Double, lo: Double, hi: Double) = v.coerceIn(lo, hi)

private fun weightedScore(
  vrNorm: Double,
  winPct: Double,
  firstsNorm: Double,
  distNorm: Double,
  dist1stNorm: Double,
): Double = W_VR * vrNorm + W_WIN * winPct + W_FIRSTS * firstsNorm + W_DIST * distNorm + W_DIST1ST * dist1stNorm

private fun toInternalVr(vrPoints: Double): Double {
  val v = clamp(vrPoints.coerceAtLeast(0.0), 0.0, MAX_VR_FANCY).roundToLong().toDouble()
  return clamp(v / 100.0, 0.0, MAX_VR_FLOAT)
}