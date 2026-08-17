package com.skiletro.wheelwitch.network

import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.model.SemVersion
import org.junit.jupiter.api.Test

class GitHubReleaseParserTest {

    @Test
    fun `parseLatestReleaseVersion extracts version from stable release title`() {
        val json = """{"name": "WheelWitch 1.0.0"}"""

        val version = GitHubReleaseParser.parseLatestReleaseVersion(json)

        assertThat(version).isEqualTo(SemVersion(1, 0, 0))
    }

    @Test
    fun `parseLatestReleaseVersion returns null when the title has no version`() {
        val json = """{"name": "Some draft"}"""

        assertThat(GitHubReleaseParser.parseLatestReleaseVersion(json)).isNull()
    }

    @Test
    fun `parseLatestReleaseVersion returns null when the release has no name`() {
        val json = """{"tag_name": "v1.0.0"}"""

        assertThat(GitHubReleaseParser.parseLatestReleaseVersion(json)).isNull()
    }

    @Test
    fun `parseLatestReleaseVersion returns null for an empty release object`() {
        assertThat(GitHubReleaseParser.parseLatestReleaseVersion("{}")).isNull()
    }

    @Test
    fun `parseLatestReleaseVersion returns null for malformed json`() {
        assertThat(GitHubReleaseParser.parseLatestReleaseVersion("not json")).isNull()
    }
}
