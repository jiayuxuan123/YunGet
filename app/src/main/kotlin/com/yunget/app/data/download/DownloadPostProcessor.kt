package com.yunget.app.data.download

import android.content.Context
import com.yunget.app.data.db.DownloadTaskEntity
import com.yunget.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

internal object DownloadPostProcessor {
    fun encodeSources(sources: List<DownloadSource>): String = JSONArray().apply {
        sources.forEach { source -> put(JSONObject().put("url", source.url)
            .put("headers", JSONObject(source.headers as Map<*, *>))) }
    }.toString()

    fun mirrors(task: DownloadTaskEntity, headers: Map<String, String>): List<String> {
        val sources = JSONArray(task.altSourcesJson)
        return buildList {
            if (task.fallbackUrl.isNotBlank()) add(task.fallbackUrl)
            for (index in 0 until sources.length()) {
                val source = sources.getJSONObject(index)
                val json = source.getJSONObject("headers")
                val sourceHeaders = json.keys().asSequence().associateWith { json.getString(it) }
                require(sourceHeaders == headers) { "多账号分片需要独立来源请求头，当前 SDK 暂不支持，请关闭实验性多源下载" }
                add(source.getString("url"))
            }
        }
    }

    suspend fun process(context: Context, task: DownloadTaskEntity, input: File): File = withContext(Dispatchers.IO) {
        if (!task.audioOnly && task.mergeAudioUrl.isBlank() && task.motionUrl.isBlank()) return@withContext input
        val directory = File(context.cacheDir, "media-processing/${task.id}").apply { mkdirs() }
        val output = File(directory, "result.${if (task.audioOnly) "m4a" else if (task.motionUrl.isNotBlank()) "jpg" else "mp4"}")
        try {
            val processingContext = coroutineContext
            processingContext.ensureActive()
            val success = when {
                task.audioOnly -> AudioExtractor.extract(input, output) { processingContext.ensureActive() }
                task.mergeAudioUrl.isNotBlank() -> {
                    val audio = File(directory, "audio.part")
                    fetch(task.mergeAudioUrl, decodeHeaders(task.mergeAudioHeadersJson), audio)
                    DashMuxer.mux(input, audio, output) { processingContext.ensureActive() }
                }
                else -> {
                    val motion = File(directory, "motion.part")
                    fetch(task.motionUrl, decodeHeaders(task.requestHeadersJson), motion)
                    MotionPhotoWriter.write(input, motion, output)
                }
            }
            coroutineContext.ensureActive()
            if (!success || output.length() == 0L) throw IOException("媒体处理失败，已保留原始下载以便重试")
            output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            File(directory, "audio.part").delete()
            File(directory, "motion.part").delete()
        }
    }

    private fun decodeHeaders(value: String): Map<String, String> = JSONObject(value.ifBlank { "{}" }).let { json ->
        json.keys().asSequence().associateWith { json.getString(it) }
    }

    internal suspend fun fetch(url: String, headers: Map<String, String>, output: File,
        client: okhttp3.OkHttpClient = HttpClients.apiClient()): Unit = coroutineScope {
        val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("媒体轨道下载失败：HTTP ${response.code}")
                val body = response.body ?: throw IOException("媒体轨道为空")
                body.byteStream().use { input -> output.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        copied += count
                    }
                    if (body.contentLength() >= 0 && copied != body.contentLength()) throw IOException("媒体轨道不完整")
                } }
            }
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            throw error
        } finally { cancellation.cancel(); call.cancel() }
    }
}
