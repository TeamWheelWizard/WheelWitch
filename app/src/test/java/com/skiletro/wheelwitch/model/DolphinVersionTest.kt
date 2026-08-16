package com.skiletro.wheelwitch.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class DolphinVersionTest {

  @ParameterizedTest
  @CsvSource(
    "2606, 2606, ",
    "2606a, 2606, ",
    "2606-300, 2606, 300",
    "5.0-2606, 2606, ",
    "5.0-2606-300, 2606, 300",
    "2407-138, 2407, 138",
  )
  fun `parse extracts year and optional dev build`(input: String, year: Int, devBuild: Int?) {
    val version = DolphinVersion.parse(input)
    assertThat(version).isNotNull()
    assertThat(version!!.year).isEqualTo(year)
    assertThat(version.devBuild).isEqualTo(devBuild)
  }

  @Test
  fun `parse ignores master minor prefix and takes first year segment`() {
    val version = DolphinVersion.parse("5.0-2606-300")
    assertThat(version).isNotNull()
    assertThat(version!!.year).isEqualTo(2606)
    assertThat(version.devBuild).isEqualTo(300)
  }

  @Test
  fun `parse returns null for garbage`() {
    assertThat(DolphinVersion.parse("nonsense")).isNull()
  }

  @Test
  fun `parse returns null for empty or null input`() {
    assertThat(DolphinVersion.parse("")).isNull()
    assertThat(DolphinVersion.parse(null)).isNull()
  }

  @Test
  fun `parse handles oversized dev build without crashing`() {
    // Unbounded digits must not throw (mirrors WheelWizard's
    // int.TryParse guard against OverflowException). The overflowed
    // dev build is dropped and the version is treated as not-supported.
    val version = DolphinVersion.parse("2606-999999999999999999999")
    assertThat(version).isNotNull()
    assertThat(version!!.devBuild).isNull()
    assertThat(version.isAtLeastMinimum()).isFalse()
  }

  @ParameterizedTest
  @MethodSource("minimumProvider")
  fun `isAtLeastMinimum against the security floor`(
    input: String,
    expected: Boolean,
  ) {
    val version = DolphinVersion.parse(input)!!
    assertThat(version.isAtLeastMinimum()).isEqualTo(expected)
  }

  @Test
  fun `minimum version is exactly supported`() {
    val version = DolphinVersion.parse("2606-300")!!
    assertThat(version.isAtLeastMinimum()).isTrue()
  }

  @Test
  fun `year-only at the floor with no dev build is below minimum`() {
    val version = DolphinVersion.parse("2606")!!
    assertThat(version.isAtLeastMinimum()).isFalse()
  }

  companion object {
    @JvmStatic
    fun minimumProvider(): Stream<Arguments> =
      Stream.of(
        Arguments.of("2606-300", true),
        Arguments.of("2606-400", true),
        Arguments.of("2607-1", true),
        Arguments.of("2606-299", false),
        Arguments.of("2606-235", false),
        Arguments.of("2407-138", false),
        Arguments.of("5.0-20132", false),
        Arguments.of("2606a", false),
        Arguments.of("2606", false),
      )
  }
}
