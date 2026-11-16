package com.yunget.app.data.download

import com.yunget.app.data.db.DownloadTaskEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadSourcePlanTest {
    @Test fun keepsPrimaryFallbackAndPerAccountHeadersInOrder() {
        val altSources = JSONArray()
            .put(JSONObject().put("url", "https://cdn.example/b")
                .put("headers", JSONObject().put("Cookie", "account-b")))
            .put(JSONObject().put("url", "https://cdn.example/c")
                .put("headers", JSONObject().put("Cookie", "account-c")))
        val task = DownloadTaskEntity(
            id = 7,
            url = "https://cdn.example/a",
            fileName = "fixture.bin",
            fallbackUrl = "https://mirror.example/a",
            altSourcesJson = altSources.toString(),
        )
        val plan = DownloadSourcePlan.forTask(task, mapOf("Cookie" to "account-a"))
        assertEquals(
            listOf("https://cdn.example/a", "https://mirror.example/a", "https://cdn.example/b", "https://cdn.example/c"),
            plan.map { it.url },
        )
        assertEquals("account-a", plan[0].headers["Cookie"])
        assertNotEquals(plan[0].headers["Cookie"], plan[2].headers["Cookie"])
    }
}
