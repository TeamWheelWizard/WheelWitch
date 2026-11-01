package com.skiletro.wheelwitch.util.json

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.junit.jupiter.api.Test

class JsonExtensionsTest {

    @Test
    fun `mapObjects skips non-object elements`() {
        val arr = JSONArray("""[{"v":"one"}, "not-an-object", 42, null, {"v":"two"}]""")

        val mapped = arr.mapObjects { it.optString("v") }

        assertThat(mapped).containsExactly("one", "two").inOrder()
    }
}