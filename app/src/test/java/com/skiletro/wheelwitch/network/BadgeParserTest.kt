package com.skiletro.wheelwitch.network

import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.model.BadgeType
import org.junit.jupiter.api.Test

class BadgeParserTest {

  @Test
  fun `parseBadgeTypes parses supported and unsupported values`() {
    val badges = parseBadgeTypes("""{"badges":[0, 100, 1000, 2000, 2018, 9999]}""")

    assertThat(badges).containsExactly(
      BadgeType.RETRO_REWIND_DEVELOPER,
      BadgeType.RWFC_MODERATOR,
      BadgeType.CONTRIBUTOR,
      BadgeType.FIRESTARTER_GOLD,
      BadgeType.BOTB_GOLD,
      BadgeType.UNKNOWN,
    ).inOrder()
  }

  @Test
  fun `parseBadgeTypes returns empty for malformed or missing badges`() {
    assertThat(parseBadgeTypes("not json")).isEmpty()
    assertThat(parseBadgeTypes("{}")).isEmpty()
    assertThat(parseBadgeTypes("{\"badges\":null}")).isEmpty()
  }
}
