package com.yunget.app.data.analytics

import org.json.JSONArray
import org.json.JSONObject

internal interface AnalyticsQueueStore {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/** Events remain durable until the server acknowledges their original IDs. */
internal class AnalyticsQueue(private val store: AnalyticsQueueStore) {
    data class Batch(val generation: Long, val events: String, val ids: Set<String>)
    private var enabled = false
    private var generation = 0L

    @Synchronized fun setEnabled(value: Boolean) {
        generation++
        enabled = value
        if (!value) store.clear()
    }

    @Synchronized fun ticket(): Long? = generation.takeIf { enabled }

    @Synchronized fun enqueue(ticket: Long, event: JSONObject): Boolean {
        if (!enabled || ticket != generation) return false
        val queue = read()
        val bucket = event.optLong("time_bucket", 0)
        if (bucket > 0) {
            val signature = signature(event)
            for (index in queue.length() - 1 downTo 0) {
                val candidate = queue.optJSONObject(index) ?: continue
                if (candidate.optLong("time_bucket", 0) != bucket || signature(candidate) != signature) continue
                val properties = candidate.optJSONObject("properties") ?: JSONObject()
                properties.put(
                    "occurrences",
                    (properties.optInt("occurrences", 1) + event.optJSONObject("properties")?.optInt("occurrences", 1)
                        .orZero()).coerceAtMost(1000),
                )
                candidate.put("properties", properties)
                queue.put(index, candidate)
                store.write(queue.toString())
                return true
            }
        }
        if (queue.length() >= 100) queue.remove(0)
        queue.put(event)
        store.write(queue.toString())
        return true
    }

    @Synchronized fun peek(): Batch? {
        if (!enabled) return null
        val queue = read()
        val batch = JSONArray()
        val ids = mutableSetOf<String>()
        for (i in 0 until minOf(8, queue.length())) {
            val entry = queue.optJSONObject(i) ?: continue
            val id = entry.optString("event_id").takeIf { it.isNotBlank() } ?: continue
            batch.put(entry)
            ids.add(id)
        }
        return if (ids.isEmpty()) null else Batch(generation, batch.toString(), ids)
    }

    @Synchronized fun acknowledge(batch: Batch) {
        if (!enabled || batch.generation != generation) return
        val queue = read()
        val remaining = JSONArray()
        for (i in 0 until queue.length()) {
            val entry = queue.optJSONObject(i) ?: continue
            if (entry.optString("event_id") !in batch.ids) remaining.put(entry)
        }
        store.write(remaining.toString())
    }

    @Synchronized fun isCurrent(batch: Batch): Boolean = enabled && batch.generation == generation

    private fun read() = runCatching {
        val raw = store.read()
        if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
    }.getOrElse { JSONArray() }

    private fun signature(event: JSONObject): String {
        val properties = event.optJSONObject("properties")?.let { JSONObject(it.toString()).apply { remove("occurrences") } }
        return listOf(
            event.optString("event"),
            event.optString("product"),
            event.optString("app_version"),
            event.optString("platform"),
            properties?.toString().orEmpty(),
        ).joinToString("|")
    }

    private fun Int?.orZero(): Int = this ?: 0
}
