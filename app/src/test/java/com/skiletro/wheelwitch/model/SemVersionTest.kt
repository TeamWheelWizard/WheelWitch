package com.skiletro.wheelwitch.model

import com.google.common.truth.Truth.assertThat
import java.util.stream.Stream
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

class SemVersionTest {

  @ParameterizedTest
  @CsvSource(
      "3.2.6, 3, 2, 6, ",
      "v3.2.6, 3, 2, 6, ",
      "V3.2.6, 3, 2, 6, ",
      "3.2.6-beta1, 3, 2, 6, beta1",
      "10.0.0-rc.2, 10, 0, 0, rc.2",
      "0.0.0, 0, 0, 0, ",
      "1.2.3-rc, 1, 2, 3, rc",
  )
  fun `parse valid versions`(
      input: String,
      major: Int,
      minor: Int,
      patch: Int,
      preRelease: String?,
  ) {
    val result = SemVersion.parse(input)
    assertThat(result).isNotNull()
    assertThat(result!!.major).isEqualTo(major)
    assertThat(result.minor).isEqualTo(minor)
    assertThat(result.patch).isEqualTo(patch)
    assertThat(result.preRelease).isEqualTo(preRelease)
  }

  @ParameterizedTest
  @MethodSource("invalidVersionProvider")
  fun `parse invalid versions returns null`(input: String) {
    assertThat(SemVersion.parse(input)).isNull()
  }

  @Test
  fun `compareTo equal versions`() {
    assertThat(SemVersion(3, 2, 6).compareTo(SemVersion(3, 2, 6))).isEqualTo(0)
  }

  @Test
  fun `compareTo version with preRelease ranks lower`() {
    assertThat(SemVersion(3, 2, 6).compareTo(SemVersion(3, 2, 6, "beta1"))).isGreaterThan(0)
    assertThat(SemVersion(3, 2, 6, "beta1").compareTo(SemVersion(3, 2, 6))).isLessThan(0)
  }

  @Test
  fun `compareTo preRelease prefixes in order`() {
    val alpha = SemVersion(3, 2, 6, "alpha")
    val beta = SemVersion(3, 2, 6, "beta")
    assertThat(alpha.compareTo(beta)).isLessThan(0)
    assertThat(beta.compareTo(alpha)).isGreaterThan(0)
  }

  @Test
  fun `compareTo preRelease numeric suffixes numerically`() {
    val beta = SemVersion.parse("3.2.6-beta")!!
    val beta2 = SemVersion.parse("3.2.6-beta2")!!
    val beta10 = SemVersion.parse("3.2.6-beta10")!!

    assertThat(beta.compareTo(beta2)).isLessThan(0)
    assertThat(beta2.compareTo(beta10)).isLessThan(0)
    assertThat(beta10.compareTo(beta2)).isGreaterThan(0)
  }

  @Test
  fun `compareTo preserves preRelease prefix ordering`() {
    val alpha = SemVersion.parse("3.2.6-alpha10")!!
    val beta = SemVersion.parse("3.2.6-beta2")!!

    assertThat(alpha.compareTo(beta)).isLessThan(0)
  }

  @Test
  fun `compareTo both have same preRelease`() {
    assertThat(SemVersion(3, 2, 6, "rc1").compareTo(SemVersion(3, 2, 6, "rc1"))).isEqualTo(0)
  }

  @ParameterizedTest
  @MethodSource("comparisonProvider")
  fun `compareTo numeric ordering`(a: SemVersion, b: SemVersion, expected: Int) {
    if (expected < 0) {
      assertThat(a.compareTo(b)).isLessThan(0)
      assertThat(b.compareTo(a)).isGreaterThan(0)
    } else if (expected > 0) {
      assertThat(a.compareTo(b)).isGreaterThan(0)
      assertThat(b.compareTo(a)).isLessThan(0)
    } else {
      assertThat(a.compareTo(b)).isEqualTo(0)
    }
  }

  @Test
  fun `toString no preRelease`() {
    assertThat(SemVersion(3, 2, 6).toString()).isEqualTo("3.2.6")
  }

  @Test
  fun `toString with preRelease`() {
    assertThat(SemVersion(3, 2, 6, "beta1").toString()).isEqualTo("3.2.6-beta1")
  }

  companion object {
    @JvmStatic
    fun comparisonProvider(): Stream<Arguments> =
        Stream.of(
            Arguments.of(SemVersion(3, 2, 6), SemVersion(3, 2, 5), 1),
            Arguments.of(SemVersion(3, 2, 5), SemVersion(3, 2, 6), -1),
            Arguments.of(SemVersion(3, 2, 6), SemVersion(3, 3, 0), -1),
            Arguments.of(SemVersion(4, 0, 0), SemVersion(3, 2, 6), 1),
            Arguments.of(SemVersion(3, 2, 6, "beta1"), SemVersion(3, 2, 6, "alpha"), 1),
            Arguments.of(SemVersion(3, 2, 6), SemVersion(3, 2, 6), 0),
        )

    @JvmStatic
    fun invalidVersionProvider(): Stream<String> =
        Stream.of(
            "3.2",
            "",
            "abc.def.ghi",
            "..",
            "v",
            "1.2.three",
        )
  }
}
