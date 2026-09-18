package com.skiletro.wheelwitch.network

import com.google.common.truth.Truth.assertThat
import com.skiletro.wheelwitch.network.parseHealthResponse
import com.skiletro.wheelwitch.network.parseLeaderboardResponse
import com.skiletro.wheelwitch.network.parseRaceStats
import com.skiletro.wheelwitch.network.parseTracks
import org.junit.jupiter.api.Test

class VersionFileParserTest {

    @Test
    fun `parseUpdatesText parses valid manifest`() {
        val text = """
            3.2.5 https://example.com/updates/3.2.5.zip /storage/path Adds feature A
            3.2.6 https://example.com/updates/3.2.6.zip /storage/path2 Fixes bug B
        """.trimIndent()

        val updates = parseUpdatesText(text)
        assertThat(updates).hasSize(2)

        assertThat(updates[0].version.major).isEqualTo(3)
        assertThat(updates[0].version.minor).isEqualTo(2)
        assertThat(updates[0].version.patch).isEqualTo(5)
        assertThat(updates[0].url).isEqualTo("https://example.com/updates/3.2.5.zip")
        assertThat(updates[0].path).isEqualTo("/storage/path")
        assertThat(updates[0].description).isEqualTo("Adds feature A")

        assertThat(updates[1].version.toString()).isEqualTo("3.2.6")
        assertThat(updates[1].url).isEqualTo("https://example.com/updates/3.2.6.zip")
    }

    @Test
    fun `parseUpdatesText skips malformed lines`() {
        val text = """
            3.2.5 https://example.com/a.zip /path desc
            bad_line_without_enough_parts
            3.2.6 https://example.com/b.zip /path2 desc2
        """.trimIndent()

        val updates = parseUpdatesText(text)
        assertThat(updates).hasSize(2)
        assertThat(updates[0].version.toString()).isEqualTo("3.2.5")
        assertThat(updates[1].version.toString()).isEqualTo("3.2.6")
    }

    @Test
    fun `parseUpdatesText skips lines with invalid version`() {
        val text = """
            3.2.5 https://a.zip /path desc
            x.y.z https://b.zip /path2 desc2
            3.2.6 https://c.zip /path3 desc3
        """.trimIndent()

        val updates = parseUpdatesText(text)
        assertThat(updates).hasSize(2)
    }

    @Test
    fun `parseUpdatesText returns empty for blank input`() {
        assertThat(parseUpdatesText("")).isEmpty()
        assertThat(parseUpdatesText("   ")).isEmpty()
        assertThat(parseUpdatesText("\n\n\n")).isEmpty()
    }

    @Test
    fun `parseUpdatesText upgrades legacy update http url to https`() {
        val text =
            "3.2.6 http://update.rwfc.net:8000/RetroRewind/zip/0017.zip /0017.zip Assets"
        val updates = parseUpdatesText(text)
        assertThat(updates).hasSize(1)
        assertThat(updates[0].url)
            .isEqualTo("https://update.rwfc.net/RetroRewind/zip/0017.zip")
    }

    @Test
    fun `parseUpdatesText leaves https update host untouched`() {
        val text =
            "6.11.6 https://update.rwfc.net/RetroRewind/zip/6.11.6.zip /6.11.6.zip Assets"
        val updates = parseUpdatesText(text)
        assertThat(updates).hasSize(1)
        assertThat(updates[0].url)
            .isEqualTo("https://update.rwfc.net/RetroRewind/zip/6.11.6.zip")
    }

    @Test
    fun `parseUpdatesText leaves https urls untouched`() {
        val text = "3.2.5 https://example.com/updates/3.2.5.zip /path desc"
        val updates = parseUpdatesText(text)
        assertThat(updates[0].url).isEqualTo("https://example.com/updates/3.2.5.zip")
    }

    @Test
    fun `parseUpdatesText upgrades scheme for http urls without port`() {
        val text = "3.2.6 http://example.com/a.zip /path desc"
        val updates = parseUpdatesText(text)
        assertThat(updates[0].url).isEqualTo("https://example.com/a.zip")
    }

    @Test
    fun `parseUpdatesText leaves non-http urls unchanged`() {
        val text = "3.2.6 notaurl /path desc"
        val updates = parseUpdatesText(text)
        assertThat(updates).hasSize(1)
        assertThat(updates[0].url).isEqualTo("notaurl")
    }

    @Test
    fun `parseDeletionsText parses valid deletion manifest`() {
        val text = """
            3.2.5 /riivolution/old/file.txt
            3.2.6 /riivolution/another.ark
        """.trimIndent()

        val deletions = parseDeletionsText(text)
        assertThat(deletions).hasSize(2)

        assertThat(deletions[0].version.toString()).isEqualTo("3.2.5")
        assertThat(deletions[0].path).isEqualTo("/riivolution/old/file.txt")

        assertThat(deletions[1].version.toString()).isEqualTo("3.2.6")
        assertThat(deletions[1].path).isEqualTo("/riivolution/another.ark")
    }

    @Test
    fun `parseDeletionsText skips malformed lines`() {
        val text = """
            3.2.5 /path/to/file
            orphan_line_without_version
            3.2.6 /another/file
        """.trimIndent()

        val deletions = parseDeletionsText(text)
        assertThat(deletions).hasSize(2)
    }

    @Test
    fun `parseDeletionsText skips lines with invalid version`() {
        val text = """
            3.2.5 /path/file
            bad.version /other/file
            3.2.6 /yet/another
        """.trimIndent()

        val deletions = parseDeletionsText(text)
        assertThat(deletions).hasSize(2)
    }

    @Test
    fun `parseDeletionsText returns empty for blank input`() {
        assertThat(parseDeletionsText("")).isEmpty()
        assertThat(parseDeletionsText("  \n\n  ")).isEmpty()
    }

    @Test
    fun `parseLeaderboardResponse handles array response`() {
        val json = """
            [
              {"rank":1,"name":"A","vr":5000,"miiName":"A","friendCode":"1234-5678-9012"},
              {"rank":2,"name":"B","vr":4000,"miiName":"B","friendCode":"9876-5432-1098"}
            ]
        """.trimIndent()
        val response = parseLeaderboardResponse(json)
        assertThat(response.entries).hasSize(2)
        assertThat(response.entries[0].rank).isEqualTo(1)
        assertThat(response.entries[0].player.name).isEqualTo("A")
        assertThat(response.entries[1].player.vr).isEqualTo(4000)
    }

    @Test
    fun `parseLeaderboardResponse handles object response with entries key`() {
        val json = """
            {"entries":[{"rank":1,"name":"A","vr":1000,"miiName":"A","friendCode":"0000-0000-0001"}],"hasMore":true}
        """.trimIndent()
        val response = parseLeaderboardResponse(json)
        assertThat(response.entries).hasSize(1)
        assertThat(response.hasMore).isTrue()
    }

    @Test
    fun `parseHealthResponse extracts all check types`() {
        val json = """
            {
              "status": "healthy",
              "checks": [
                {"name": "LeaderboardDbContext", "status": "healthy", "description": "DB ok"},
                {"name": "npgsql", "status": "healthy"},
                {"name": "retro-wfc-api", "status": "healthy"},
                {"name": "memory", "status": "healthy", "description": "Memory usage: 1024 MB"}
              ]
            }
        """.trimIndent()
        val health = parseHealthResponse(json)
        assertThat(health.status).isEqualTo("ok")
        assertThat(health.database?.status).isEqualTo("ok")
        assertThat(health.postgresql?.status).isEqualTo("ok")
        assertThat(health.retroWfcApi?.status).isEqualTo("ok")
        assertThat(health.memory?.status).isEqualTo("ok")
        assertThat(health.memory?.used).isEqualTo("1024MB")
    }

    @Test
    fun `parseTracks handles both array and object response`() {
        val arrJson = """[{"id":1,"name":"Track One"},{"id":2,"name":"Track Two"}]"""
        val tracks1 = parseTracks(arrJson)
        assertThat(tracks1).hasSize(2)
        assertThat(tracks1[0].name).isEqualTo("Track One")
        assertThat(tracks1[0].id).isEqualTo(1)

        val objJson = """{"tracks":[{"id":3,"name":"Track Three"}]}"""
        val tracks2 = parseTracks(objJson)
        assertThat(tracks2).hasSize(1)
        assertThat(tracks2[0].id).isEqualTo(3)
    }

    @Test
    fun `parseRaceStats handles full and partial stats`() {
        val full = """
            {
              "totalRacesTracked": 1000,
              "uniquePlayersCount": 50,
              "trackedSince": "2024-01-01",
              "allPlayedTracks": [{"trackName":"Track A","raceCount":100}],
              "mostActivePlayers": [{"name":"P1","pid":"p1","fc":"0000","raceCount":50}],
              "topCharactersByWinRate": [{"name":"Mario","raceCount":10,"winCount":9,"winRate":0.9}]
            }
        """.trimIndent()
        val stats = parseRaceStats(full)
        assertThat(stats.totalRaces).isEqualTo(1000)
        assertThat(stats.totalPlayers).isEqualTo(50)
        assertThat(stats.trackedSince).isEqualTo("2024-01-01")
        assertThat(stats.allPlayedTracks).hasSize(1)
        assertThat(stats.mostActivePlayers).hasSize(1)
        assertThat(stats.topCharactersByWinRate).hasSize(1)

        val partial = """{"totalRacesTracked":42}"""
        val stats2 = parseRaceStats(partial)
        assertThat(stats2.totalRaces).isEqualTo(42)
        assertThat(stats2.totalPlayers).isEqualTo(0)
    }
}
