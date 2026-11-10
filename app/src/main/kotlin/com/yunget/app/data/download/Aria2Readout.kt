package com.yunget.app.data.download

import java.util.concurrent.atomic.AtomicReference

internal data class Aria2Readout(val completed: Long, val total: Long, val connections: Int) {
    companion object {
        private val bytes = Regex("""(\d+)B?/(\d+)B?""")
        private val connections = Regex("""\bCN:(\d+)\b""")

        fun parse(line: String): Aria2Readout? {
            if (!line.contains("[#")) return null
            val counts = bytes.find(line) ?: return null
            val done = counts.groupValues[1].toLongOrNull() ?: return null
            val total = counts.groupValues[2].toLongOrNull() ?: return null
            if (total > 0 && done > total) return null
            val active = connections.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return Aria2Readout(done, total, active)
        }
    }
}

/** Poll only the engine's byte counter: random writes can extend a partial file to its full logical size. */
internal class Aria2ProgressMonitor {
    data class Update(val completed: Long, val delta: Long, val connections: Int, val initial: Boolean)
    private val latest = AtomicReference<Aria2Readout?>(null)
    private var reported: Long? = null

    fun accept(line: String) {
        val next = Aria2Readout.parse(line) ?: return
        latest.updateAndGet { previous ->
            next.copy(completed = maxOf(previous?.completed ?: 0L, next.completed))
        }
    }

    fun poll(): Update? {
        val sample = latest.get() ?: return null
        val previous = reported
        reported = sample.completed
        return Update(sample.completed, if (previous == null) 0L else sample.completed - previous, sample.connections, previous == null)
    }
}
