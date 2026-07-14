package com.eklos.astraia

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.RandomAccessFile

/**
 * Lightweight CPU utilisation monitor that reads `/proc/stat` at a fixed
 * interval and emits per-core + aggregate utilisation as a [CpuSample].
 *
 * ## Design
 *
 * - Runs on a dedicated low-priority [HandlerThread] so `/proc/stat` I/O
 *   never blocks the main thread.
 * - Sampling interval is configurable (default 200 ms).
 * - Exposes a [Flow] that downstream consumers (e.g. [PerformanceStreamUseCase])
 *   can combine with other sensor data.
 *
 * ## /proc/stat Format
 *
 * ```
 * cpu  user nice system idle iowait irq softirq steal guest guest_nice
 * cpu0 user nice system idle iowait irq softirq steal guest guest_nice
 * ```
 *
 * The first token on each line is stripped; the remaining numbers are summed
 * to get `total`, and `idle + iowait` (fields 4+5 in the 0-indexed column
 * list after the label) is treated as idle.
 *
 * ## Thread Safety
 *
 * All mutable state is confined to the handler thread.  The [Flow] callback
 * runs on the handler thread's [Looper].
 */
class CpuMonitor private constructor() {

    // ── Data classes ──────────────────────────────────────────────

    /**
     * One snapshot of CPU utilisation.
     *
     * @property overallPercent  0–100 aggregate CPU usage across all cores.
     * @property corePercents    0–100 per-core usage (index = core number).
     * @property coreCount       number of CPU cores detected.
     * @property timestampMs     monotonic timestamp of this sample.
     */
    data class CpuSample(
        val overallPercent: Float,
        val corePercents: List<Float>,
        val coreCount: Int,
        val timestampMs: Long
    )

    // ── Internal types ────────────────────────────────────────────

    /** Parsed raw counters for one CPU (aggregate or single core). */
    private data class CpuCounters(
        val total: Long,
        val idle: Long
    )

    // ── Threading ─────────────────────────────────────────────────

    private val handlerThread = HandlerThread(
        "CpuMonitor",
        android.os.Process.THREAD_PRIORITY_BACKGROUND
    )
    private val handler: Handler by lazy {
        handlerThread.start()
        Handler(handlerThread.looper)
    }

    @Volatile
    private var sampling = false

    // ── Public API ────────────────────────────────────────────────

    /**
     * Start sampling and return a cold [Flow] that emits [CpuSample] at
     * the configured interval.
     *
     * Only one collector may be active — collecting multiple times
     * shares the same underlying sampler.
     *
     * @param intervalMs  sampling period in milliseconds (default 200).
     */
    fun samples(intervalMs: Long = 200L): Flow<CpuSample> = callbackFlow {
        // Sampler: stores previous counters in a field, updated each tick.
        val sampler = object : Runnable {
            private var prevCounters: List<CpuCounters>? = readAllCpuCounters()

            override fun run() {
                if (!sampling) return
                try {
                    val nowCounters = readAllCpuCounters()
                    val p = prevCounters
                    if (nowCounters != null && p != null && nowCounters.size == p.size) {
                        val nowTs = System.nanoTime()
                        val overall = computePercent(p[0], nowCounters[0])
                        val corePercents = (1 until nowCounters.size).map {
                            computePercent(p[it], nowCounters[it])
                        }
                        trySend(
                            CpuSample(
                                overallPercent = overall,
                                corePercents = corePercents,
                                coreCount = nowCounters.size - 1,
                                timestampMs = nowTs / 1_000_000L
                            )
                        )
                    }
                    prevCounters = nowCounters
                } catch (e: Exception) {
                    Log.w(TAG, "CpuMonitor read failed: ${e.message}")
                }
                if (sampling) handler.postDelayed(this, intervalMs)
            }
        }

        sampling = true
        handler.postDelayed(sampler, intervalMs)

        awaitClose {
            sampling = false
            handler.removeCallbacks(sampler)
        }
    }

    /**
     * Stop the background thread entirely.  Call this at app shutdown.
     */
    fun shutdown() {
        sampling = false
        handlerThread.quitSafely()
    }

    // ── Parsing helpers ───────────────────────────────────────────

    /**
     * Read `/proc/stat` and parse all CPU lines.
     *
     * @return list of [CpuCounters] — index 0 is aggregate, indices
     *         1..N are per-core, or null on I/O failure.
     */
    private fun readAllCpuCounters(): List<CpuCounters>? {
        return try {
            val raf = RandomAccessFile(PROC_STAT, "r")
            val lines = mutableListOf<CpuCounters>()
            var line: String? = raf.readLine()
            while (line != null) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("cpu")) {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 5) {
                        val label = parts[0]
                        // Accept "cpu" and numbered "cpuN"
                        if (label == "cpu" || label.length > 3) {
                            val fields = parts.drop(1).mapNotNull { it.toLongOrNull() }
                            if (fields.size >= 4) {
                                val total = fields.sum()
                                // idle = idle (index 3) + iowait (index 4 if present)
                                val idle = fields[3] + (fields.getOrNull(4) ?: 0L)
                                lines += CpuCounters(total = total, idle = idle)
                            }
                        }
                    }
                } else if (trimmed.startsWith("intr")) {
                    // Stop after CPU lines — the rest of /proc/stat is irrelevant
                    break
                }
                line = raf.readLine()
            }
            raf.close()
            if (lines.isEmpty()) null else lines
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $PROC_STAT: ${e.message}")
            null
        }
    }

    /**
     * Compute utilisation percent from two [CpuCounters] snapshots.
     *
     * @return value in [0, 100], or 0 if the delta is zero.
     */
    private fun computePercent(prev: CpuCounters, now: CpuCounters): Float {
        val totalDelta = now.total - prev.total
        val idleDelta = now.idle - prev.idle
        if (totalDelta <= 0L) return 0f
        val pct = (1.0f - idleDelta.toFloat() / totalDelta.toFloat()) * 100f
        return pct.coerceIn(0f, 100f)
    }

    // ── Companion ─────────────────────────────────────────────────

    companion object {
        private const val TAG = "CpuMonitor"
        private const val PROC_STAT = "/proc/stat"

        @Volatile
        private var instance: CpuMonitor? = null

        /** Get or create the singleton. */
        fun getInstance(): CpuMonitor {
            return instance ?: synchronized(this) {
                instance ?: CpuMonitor().also { instance = it }
            }
        }
    }
}
