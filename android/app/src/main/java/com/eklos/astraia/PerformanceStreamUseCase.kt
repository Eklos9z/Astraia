package com.eklos.astraia

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive

/**
 * A single combined snapshot of real-time engine + system performance.
 *
 * @property cpuPercent     aggregate CPU usage 0–100.
 * @property corePercents   per-core CPU usage percentages.
 * @property nps            engine nodes-per-second (0 when idle).
 * @property nodeCount      total nodes searched (0 when idle).
 * @property timestampMs    monotonic timestamp of this snapshot.
 */
data class PerfSnapshot(
    val cpuPercent: Float,
    val corePercents: List<Float>,
    val nps: Long,
    val nodeCount: Long = 0L,
    val timestampMs: Long = System.nanoTime() / 1_000_000L
)

/**
 * Combines the [CpuMonitor] CPU data stream with engine NPS readings
 * into a single [PerfSnapshot] [Flow] for consumption by the UI layer.
 *
 * ## Data Pipeline
 *
 * ```
 * ┌──────────────┐   ┌──────────────────────┐
 * │ CpuMonitor    │──▶│ cpuFlow              │
 * │ /proc/stat   │   │ (every 200ms)        │
 * └──────────────┘   └──────────┬───────────┘
 *                               │ combine()     ┌──────────────────┐
 *                               ├──────────────▶│ perfSnapshots    │
 *                               │               │ sample(200ms)    │
 * ┌──────────────┐   ┌──────────┴───────────┐   └──────────────────┘
 * │ EdaxCont.    │──▶│ npsFlow              │
 * │ Bridge       │   │ (poll every 200ms)   │
 * └──────────────┘   └──────────────────────┘
 * ```
 *
 * @param cpuMonitor    singleton CPU monitor.
 * @param engineBridge  singleton JNI bridge for NPS readings.
 */
class PerformanceStreamUseCase(
    private val cpuMonitor: CpuMonitor,
    private val engineBridge: EdaxContinuousBridge
) {
    /**
     * Return a [Flow] that emits combined performance snapshots at
     * approximately [intervalMs] intervals.
     *
     * Both input streams are sampled independently at the same rate,
     * combined via [combine], and a final [sample] guard ensures the
     * downstream UI never receives more than one emission per interval.
     *
     * @param intervalMs  emission interval in ms (default 200).
     */
    fun perfSnapshots(intervalMs: Long = 200L): Flow<PerfSnapshot> {
        // CPU utilisation from /proc/stat — already pulses at intervalMs.
        val cpuFlow: Flow<CpuMonitor.CpuSample> = cpuMonitor.samples(intervalMs)

        // NPS — polled from the engine bridge since it's a property, not a flow.
        val npsFlow: Flow<Long> = flow {
            while (currentCoroutineContext().isActive) {
                val nps = engineBridge.engineNps.toLong()
                emit(nps)
                delay(intervalMs)
            }
        }

        // Node count — polled alongside NPS.
        val nodeCountFlow: Flow<Long> = flow {
            while (currentCoroutineContext().isActive) {
                emit(engineBridge.engineNodeCount)
                delay(intervalMs)
            }
        }

        return combine(cpuFlow, npsFlow, nodeCountFlow) { cpu, nps, nodes ->
            PerfSnapshot(
                cpuPercent   = cpu.overallPercent,
                corePercents = cpu.corePercents,
                nps          = nps,
                nodeCount    = nodes,
                timestampMs  = cpu.timestampMs
            )
        }.sample(intervalMs)  // safety throttle in case of burst
    }
}
