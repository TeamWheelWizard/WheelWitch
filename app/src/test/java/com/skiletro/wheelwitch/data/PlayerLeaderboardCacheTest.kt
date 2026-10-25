package com.skiletro.wheelwitch.data

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.model.PlayerLeaderboardData
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Test

class PlayerLeaderboardCacheTest {

  @Test
  fun `decodeLeaderboardCache round-trips vr name and miiData`() {
    val entries =
      mapOf(
        "AAAA-AAAA-AAAA" to PlayerLeaderboardData(vr = 4321, name = "Mii", miiData = "QUJD"),
      )

    val decoded = decodeLeaderboardCache(encodeLeaderboardCache(entries))

    assertThat(decoded).isEqualTo(entries)
  }

  @Test
  fun `decodeLeaderboardCache keeps null name and miiData as null`() {
    val entries = mapOf("BBBB-BBBB-BBBB" to PlayerLeaderboardData(vr = 99, name = null, miiData = null))

    val decoded = decodeLeaderboardCache(encodeLeaderboardCache(entries))

    assertThat(decoded).isEqualTo(entries)
  }

  @Test
  fun `decodeLeaderboardCache returns an empty map for blank or malformed JSON`() {
    assertThat(decodeLeaderboardCache("")).isEmpty()
    assertThat(decodeLeaderboardCache("   ")).isEmpty()
    assertThat(decodeLeaderboardCache("not json")).isEmpty()
  }

  @Test
  fun `store load returns null when nothing has been saved`() {
    val cache = PrefsPlayerLeaderboardCache(mockPrefs(mutableMapOf()), key = "leaderboard")

    assertThat(cache.load("AAAA-AAAA-AAAA")).isNull()
  }

  @Test
  fun `store save then load returns the saved data`() {
    val cache = PrefsPlayerLeaderboardCache(mockPrefs(mutableMapOf()), key = "leaderboard")
    val data = PlayerLeaderboardData(vr = 123, name = "Net", miiData = "QUJD")

    cache.save("AAAA-AAAA-AAAA", data)

    assertThat(cache.load("AAAA-AAAA-AAAA")).isEqualTo(data)
  }

  @Test
  fun `store save for one friend code keeps entries for other friend codes`() {
    val cache = PrefsPlayerLeaderboardCache(mockPrefs(mutableMapOf()), key = "leaderboard")

    cache.save("AAAA-AAAA-AAAA", PlayerLeaderboardData(vr = 1, name = "A", miiData = null))
    cache.save("BBBB-BBBB-BBBB", PlayerLeaderboardData(vr = 2, name = "B", miiData = null))

    assertThat(cache.load("AAAA-AAAA-AAAA"))
      .isEqualTo(PlayerLeaderboardData(vr = 1, name = "A", miiData = null))
  }

  private fun mockPrefs(storage: MutableMap<String, String?>): SharedPreferences {
    val prefs = mockk<SharedPreferences>()
    every { prefs.getString(any(), any()) } answers { storage[firstArg()] }

    val editor = mockk<SharedPreferences.Editor>()
    every { editor.putString(any(), any()) } answers {
      storage[firstArg()] = secondArg()
      editor
    }
    every { editor.apply() } just runs
    every { prefs.edit() } returns editor
    return prefs
  }
}