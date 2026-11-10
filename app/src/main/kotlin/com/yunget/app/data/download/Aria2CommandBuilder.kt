package com.yunget.app.data.download

import java.io.File
import java.security.MessageDigest

/** The actual argv used by the optional aria2-next process. */
internal object Aria2CommandBuilder {
    const val MAX_CONNECTIONS = 256

    fun connections(requested: Int): Int = requested.coerceIn(1, MAX_CONNECTIONS)

    fun build(binary: File, caBundle: File?, spec: DownloadEngineRunner.RunSpec): List<String> = buildList {
        add(binary.absolutePath)
        add("-d"); add(spec.chunkDir.absolutePath)
        add("-o"); add(Aria2Runner.OUT_NAME)
        add("-c")
        // aria2-next recovery requires the original task identity and recovery database.
        val identity = "yunget-aria2-v1\n${spec.taskId}\n${spec.chunkDir.canonicalPath}"
        val gid = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        add("--gid=$gid")
        add("--state-dir=${File(spec.chunkDir, "aria2-state").absolutePath}")
        add("--stream-max-connections=${connections(spec.targetWorkers)}")
        add("--file-allocation=none")
        add("--auto-file-renaming=false")
        add("--allow-overwrite=true")
        add("--no-conf"); add("-n")
        add("--console-log-level=warn")
        caBundle?.let {
            add("--ca-certificate=${it.absolutePath}")
            add("--check-certificate=true")
        }
        add("--show-console-readout=true")
        add("--summary-interval=0")
        add("--human-readable=false")
        if (spec.speedLimitBytesPerSec > 0) add("--max-overall-download-limit=${spec.speedLimitBytesPerSec}")
        spec.headers.forEach { (name, value) ->
            if (!name.equals("Host", true) && !name.equals("Content-Length", true)) add("--header=$name: $value")
        }
        add(spec.url)
    }
}
