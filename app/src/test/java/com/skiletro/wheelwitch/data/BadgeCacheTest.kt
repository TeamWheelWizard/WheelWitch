package com.skiletro.wheelwitch.data

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.model.BadgeType
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Test

class BadgeCacheTest {

  @Test
  fun `decodeBadgeCache round-trips multiple badges per profile`() {
    val entries =
      mapOf(
        123L to listOf(BadgeType.SUPPORTER, BadgeType.CONTRIBUTOR),
        456L to listOf(BadgeType.RETRO_REWIND_DEVELOPER),
      )

    val decoded = decodeBadgeCache(encodeBadgeCache(entries))

    assertThat(decoded).isEqualTo(entries)
  }

  @Test
  fun `decodeBadgeCache drops UNKNOWN badges`() {
    val encoded = encodeBadgeCache(mapOf(123L to listOf(BadgeType.UNKNOWN, BadgeType.SUPPORTER)))

    assertThat(decodeBadgeCache(encoded)).isEqualTo(mapOf(123L to listOf(BadgeType.SUPPORTER)))
  }

  @Test
  fun `decodeBadgeCache returns an empty map for blank or malformed JSON`() {
    assertThat(decodeBadgeCache("")).isEmpty()
    assertThat(decodeBadgeCache("   ")).isEmpty()
    assertThat(decodeBadgeCache("not json")).isEmpty()
  }

  @Test
  fun `store load returns null when nothing has been saved`() {
    val cache = PrefsBadgeCache(mockPrefs(mutableMapOf()), key = "badges")

    assertThat(cache.load(123L)).isNull()
  }

  @Test
  fun `store save then load returns the saved badges`() {
    val cache = PrefsBadgeCache(mockPrefs(mutableMapOf()), key = "badges")
    val badges = listOf(BadgeType.SUPPORTER, BadgeType.HEART)

    cache.save(123L, badges)

    assertThat(cache.load(123L)).isEqualTo(badges)
  }

  @Test
  fun `store save for one profile keeps entries for other profiles`() {
    val cache = PrefsBadgeCache(mockPrefs(mutableMapOf()), key = "badges")

    cache.save(123L, listOf(BadgeType.SUPPORTER))
    cache.save(456L, listOf(BadgeType.CONTRIBUTOR))

    assertThat(cache.load(123L)).isEqualTo(listOf(BadgeType.SUPPORTER))
    assertThat(cache.load(456L)).isEqualTo(listOf(BadgeType.CONTRIBUTOR))
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