package com.skiletro.wheelwitch.network

import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.model.SemVersion
import org.junit.jupiter.api.Test

class GitHubReleaseParserTest {

    @Test
    fun `parseLatestReleaseVersion extracts version from ci release title`() {
        val json = """[{"name": "WheelWitch 0.301.0 (abc1234)"}]"""

        val version = GitHubReleaseParser.parseLatestReleaseVersion(json)

        assertThat(version).isEqualTo(SemVersion(0, 301, 0))
    }

    @Test
    fun `parseLatestReleaseVersion takes the first of many releases`() {
        val json =
            """
            [
              {"name": "WheelWitch 0.302.0 (def5678)"},
              {"name": "WheelWitch 0.301.0 (abc1234)"}
            ]
            """.trimIndent()

        assertThat(GitHubReleaseParser.parseLatestReleaseVersion(json))
            .isEqualTo(SemVersion(0, 302, 0))
    }

    @Test
    fun `parseLatestReleaseVersion returns null when the title has no version`() {
        val json = """[{"name": "Some draft"}]"""

        assertThat(GitHubReleaseParser.parseLatestReleaseVersion(json)).isNull()
    }

    @Test
    fun `parseLatestReleaseVersion returns null when the release has no name`() {
        val json = """[{"tag_name": "ci"}]"""

        assertThat(GitHubReleaseParser.parseLatestReleaseVersion(json)).isNull()
    }

    @Test
    fun `parseLatestReleaseVersion returns null for an empty release list`() {
        assertThat(GitHubReleaseParser.parseLatestReleaseVersion("[]")).isNull()
    }

    @Test
    fun `parseLatestReleaseVersion returns null for malformed json`() {
        assertThat(GitHubReleaseParser.parseLatestReleaseVersion("not json")).isNull()
    }
}
