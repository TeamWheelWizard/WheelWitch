package com.skiletro.wheelwitch.domain

import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.model.LicenseStats
import org.junit.jupiter.api.Test

class RankCalculatorTest {

  private fun stats(
    vrPoints: Double = 0.0,
    wins: Int = 0,
    losses: Int = 0,
    firsts: Int = 0,
    dist: Double = 0.0,
    dist1st: Double = 0.0,
  ) = LicenseStats(vrPoints, wins, losses, firsts, dist, dist1st)

  @Test
  fun `score is zero and rank zero until the race-match requirement is met`() {
    val result = computeScore(stats(vrPoints = 5000.0, wins = 10, losses = 10))
    assertThat(result.meetsRaceReq).isFalse()
    assertThat(result.score).isEqualTo(0.0)
    assertThat(result.rank).isEqualTo(0)
  }

  @Test
  fun `computeScore normalises and blends all five stats`() {
    val result =
        computeScore(
            stats(
              vrPoints = 50000.0, // internal 500 -> norm 50
              wins = 60,
              losses = 40, // 60% win, 100 total
              firsts = 2250, // 100%
              dist = 40000.0, // 100%
              dist1st = 10000.0, // 100%
            )
        )
    assertThat(result.meetsRaceReq).isTrue()
    assertThat(result.vrNorm).isEqualTo(50.0)
    assertThat(result.winPct).isEqualTo(60.0)
    assertThat(result.firstsNorm).isEqualTo(100.0)
    assertThat(result.distNorm).isEqualTo(100.0)
    assertThat(result.dist1stNorm).isEqualTo(100.0)
    assertThat(result.score).isWithin(0.01).of(68.19)
    assertThat(result.rank).isEqualTo(5)
  }

  @Test
  fun `rankFromScore maps the score bands to ranks`() {
    assertThat(rankFromScore(100.0)).isEqualTo(9)
    assertThat(rankFromScore(94.0)).isEqualTo(8)
    assertThat(rankFromScore(84.0)).isEqualTo(7)
    assertThat(rankFromScore(72.0)).isEqualTo(6)
    assertThat(rankFromScore(60.0)).isEqualTo(5)
    assertThat(rankFromScore(48.0)).isEqualTo(4)
    assertThat(rankFromScore(36.0)).isEqualTo(3)
    assertThat(rankFromScore(24.0)).isEqualTo(2)
    assertThat(rankFromScore(0.0)).isEqualTo(1)
  }

  @Test
  fun `nextThreshold returns the next rank floor and null at the max rank`() {
    assertThat(nextThreshold(1)).isEqualTo(24.0)
    assertThat(nextThreshold(5)).isEqualTo(72.0)
    assertThat(nextThreshold(8)).isEqualTo(100.0)
    assertThat(nextThreshold(9)).isNull()
  }

  @Test
  fun `wouldBeScore reproduces computeScore for the same stats`() {
    val result =
        computeScore(
            stats(
              vrPoints = 5000.0,
              wins = 60,
              losses = 40,
              firsts = 2250,
              dist = 40000.0,
              dist1st = 10000.0,
            )
        )
    assertThat(wouldBeScore(result)).isEqualTo(result.score)
    assertThat(wouldBeRank(result)).isEqualTo(result.rank)
  }

  @Test
  fun `computeNeeds reports max rank when the score is crown-ranked`() {
    val needs =
        computeNeeds(
            stats(
              vrPoints = 100000.0,
              wins = 60,
              losses = 40,
              firsts = 2250,
              dist = 40000.0,
              dist1st = 10000.0,
            )
        )
    assertThat(needs.isMaxRank).isTrue()
    assertThat(needs.threshold).isNull()
    assertThat(needs.vr.neededNorm).isEqualTo(0.0)
  }

  @Test
  fun `computeNeeds reports per-stat needs and feasibility for a mid score`() {
    // rank 8 (94 <= score < 100); next threshold is 100.
    val needs =
        computeNeeds(
            stats(
              vrPoints = 90000.0, // vrNorm 90
              wins = 80,
              losses = 20, // winPct 80, 100 total
              firsts = 2000, // ~88.89%
              dist = 40000.0,
              dist1st = 10000.0,
            )
        )
    assertThat(needs.isMaxRank).isFalse()
    assertThat(needs.threshold).isEqualTo(100.0)
    assertThat(needs.vr.neededNorm).isWithin(0.01).of(96.53)
    assertThat(needs.vr.neededRaw).isEqualTo(96600.0)
    assertThat(needs.vr.feasibility).isEqualTo("ok")
    assertThat(needs.winPct.feasibility).isEqualTo("warn")
    assertThat(needs.firsts.feasibility).isEqualTo("infeasible")
    assertThat(needs.firsts.neededRaw).isEqualTo(2250.0)
    assertThat(needs.dist.neededRaw).isEqualTo(40000.0)
    assertThat(needs.dist1st.neededRaw).isEqualTo(10000.0)
  }

  @Test
  fun `rank tables are immutable lists`() {
    assertThat(RANK_THRESH).containsExactly(0.0, 24.0, 36.0, 48.0, 60.0, 72.0, 84.0, 94.0, 100.0).inOrder()
    assertThat(RANK_NAMES).containsExactly("E", "D", "C", "B", "A", "1star", "2star", "3star", "Crown").inOrder()
  }
}