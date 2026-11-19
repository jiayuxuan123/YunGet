package com.yunget.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class DesktopResolveRecord(
    val id: String = UUID.randomUUID().toString(),
    val input: String,
    val passcode: String = "",
    val title: String = "",
    val platformLabel: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

internal class DesktopResolveHistoryStore(private val dataDirectory: File) {
    private val historyFile = File(dataDirectory, "resolve-history.json")
    private val favoritesFile = File(dataDirectory, "resolve-favorites.json")

    private val historyState = MutableStateFlow(loadList(historyFile))
    private val favoritesState = MutableStateFlow(loadList(favoritesFile))

    val history: StateFlow<List<DesktopResolveRecord>> = historyState.asStateFlow()
    val favorites: StateFlow<List<DesktopResolveRecord>> = favoritesState.asStateFlow()

    fun recordHistory(
        input: String,
        passcode: String,
        title: String,
        platformLabel: String,
    ) {
        val normalized = input.trim()
        if (normalized.isEmpty()) return
        val entry = DesktopResolveRecord(
            input = normalized,
            passcode = passcode.trim(),
            title = title.trim().ifBlank { normalized.take(64) },
            platformLabel = platformLabel.trim(),
            timestamp = System.currentTimeMillis(),
        )
        val updated = buildList {
            add(entry)
            historyState.value
                .filterNot { it.input.equals(normalized, ignoreCase = true) }
                .take(99)
                .forEach(::add)
        }
        historyState.value = updated
        saveList(historyFile, updated)
    }

    fun isFavorited(input: String): Boolean {
        val normalized = input.trim()
        if (normalized.isEmpty()) return false
        return favoritesState.value.any { it.input.equals(normalized, ignoreCase = true) }
    }

    fun toggleFavorite(
        input: String,
        passcode: String,
        title: String,
        platformLabel: String,
    ): Boolean {
        val normalized = input.trim()
        if (normalized.isEmpty()) return false
        val existing = favoritesState.value.firstOrNull { it.input.equals(normalized, ignoreCase = true) }
        return if (existing != null) {
            val next = favoritesState.value.filterNot { it.id == existing.id }
            favoritesState.value = next
            saveList(favoritesFile, next)
            false
        } else {
            val entry = DesktopResolveRecord(
                input = normalized,
                passcode = passcode.trim(),
                title = title.trim().ifBlank { normalized.take(64) },
                platformLabel = platformLabel.trim(),
                timestamp = System.currentTimeMillis(),
            )
            val next = listOf(entry) + favoritesState.value
            favoritesState.value = next
            saveList(favoritesFile, next)
            true
        }
    }

    fun removeHistory(id: String) {
        val next = historyState.value.filterNot { it.id == id }
        historyState.value = next
        saveList(historyFile, next)
    }

    fun clearHistory() {
        historyState.value = emptyList()
        saveList(historyFile, emptyList())
    }

    fun removeFavorite(id: String) {
        val next = favoritesState.value.filterNot { it.id == id }
        favoritesState.value = next
        saveList(favoritesFile, next)
    }

    private fun loadList(file: File): List<DesktopResolveRecord> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val input = obj.optString("input").trim()
                    if (input.isEmpty()) continue
                    add(
                        DesktopResolveRecord(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            input = input,
                            passcode = obj.optString("passcode"),
                            title = obj.optString("title"),
                            platformLabel = obj.optString("platformLabel"),
                            timestamp = obj.optLong("timestamp", 0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveList(file: File, items: List<DesktopResolveRecord>) {
        val arr = JSONArray()
        items.forEach { rec ->
            arr.put(
                JSONObject()
                    .put("id", rec.id)
                    .put("input", rec.input)
                    .put("passcode", rec.passcode)
                    .put("title", rec.title)
                    .put("platformLabel", rec.platformLabel)
                    .put("timestamp", rec.timestamp)
            )
        }
        Files.createDirectories(file.parentFile.toPath())
        val tmp = file.resolveSibling("${file.name}.tmp")
        tmp.writeText(arr.toString(2))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
