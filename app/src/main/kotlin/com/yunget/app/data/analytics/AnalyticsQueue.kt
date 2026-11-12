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
}
