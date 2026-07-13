package com.eklos.astraia

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/**
 * A single atomic update emitted by the engine during iterative deepening.
 *
 * @property depth      current search depth (ply)
 * @property score      current best score in centi-discs (+1 = +0.01 disc advantage)
 * @property nodes      total nodes visited so far
 * @property timeMs     elapsed search time in milliseconds
 * @property bestMove   current best move (e.g. "f5")
 * @property movesLeft  number of moves remaining to be fully evaluated
 */
data class EvalUpdate(
    val depth: Int,
    val score: Int,
    val nodes: Long,
    val timeMs: Long,
    val bestMove: String,
    val movesLeft: Int
)

/**
 * Per-move evaluation bounds from the engine.
 *
 * @property move  coordinate (e.g. "f5")
 * @property lo    lower bound of score for this move
 * @property hi    upper bound of score for this move
 */
data class MoveBound(
    val move: String,
    val lo: Int,
    val hi: Int,
    val depth: Int = 0,      // global depth at time of emission
    val nodes: Long = 0L,    // global nodes at time of emission
    val isPv: Boolean = false, // true if this is the PV (best) move with exact score
    val discard: Boolean = false // true if this move is >10 discs behind PV;
                                 // Kotlin merge removes it from the display
)

/**
 * JNI-powered continuous-evaluation bridge.
 *
 * ## Lifecycle
 *
 * 1. Call [init] once (typically in Application.onCreate).
 * 2. Call [registerCallback] to wire the native observer into a Kotlin [SharedFlow].
 * 3. Call [startContinuousSearch] to launch a search on a native worker thread.
 * 4. Collect [evalFlow] in your UI layer — updates arrive throttled at ~100-200ms.
 * 5. Call [stopSearch] to abort or when the user navigates away.
 *
 * ## Thread Safety
 *
 * All public methods are safe to call from any thread. The native observer
 * fires from the Edax search worker thread and is marshalled onto
 * [Dispatchers.Default] before entering the Flow pipeline.
 *
 * ## Thermal Integration
 *
 * Use [setThreadCount] to decrease/increase worker threads at runtime in
 * response to thermal events — no restart needed.
 */
object EdaxContinuousBridge {

    // ── JNI native declarations ──────────────────────────────────

    @JvmStatic private external fun nativeContInit(): Boolean
    @JvmStatic private external fun nativeRegisterCallback(callback: Any)
    @JvmStatic private external fun nativeStartContinuousSearch(
        board: String, level: Int, moveTimeMs: Long
    ): Boolean
    @JvmStatic private external fun nativeStopSearch()
    @JvmStatic private external fun nativeSetThreadCount(n: Int)
    @JvmStatic private external fun nativeGetThreadCount(): Int
    @JvmStatic private external fun nativeGetMoveBounds(): String
    @JvmStatic private external fun nativeIsSearchRunning(): Boolean
    @JvmStatic private external fun nativeRequestSnapshot()

    // ── Internal state ──────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Monotonic epoch counter incremented on every [startContinuousSearch].
     *
     * The [SearchUpdateCallback] snapshots the current epoch and discards any
     * emission that arrives after a newer search has started.  This prevents
     * a race where an old native search thread fires its observer callback
     * AFTER the next [startContinuousSearch] has already launched, which
     * would otherwise overwrite fresh bounds with stale data.
     */
    @Volatile
    private var searchEpoch: Int = 0

    /**
     * Hot [SharedFlow] that carries live search updates.
     *
     * - replay = 0 — late subscribers only see future emissions.
     * - extraBufferCapacity = 4 — small backpressure window.
     * - onBufferOverflow = DROP_OLDEST — the UI cares about the latest state.
     *
     * As the native observer can fire many times per second (once per depth
     * iteration), we apply [Flow.sample] throttling downstream (see [evalFlow]).
     */
    private val _rawEvalFlow = MutableSharedFlow<EvalUpdate>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Public consumption point.
     *
     * Throttled to emit at most once every [THROTTLE_MS] (default 120ms),
     * which keeps the Compose UI at a smooth ~8 Hz while still feeling "live."
     *
     * ```
     * ┌──────────┐    ┌───────────┐    ┌────────────┐    ┌──────────────┐
     * │ observer │───▶│ _rawEval  │───▶│  sample()  │───▶│  evalFlow    │
     * │ 50+ Hz   │    │ SharedFlow│    │  120 ms    │    │ ~8 Hz stable │
     * └──────────┘    └───────────┘    └────────────┘    └──────────────┘
     * ```
     */
    val evalFlow: Flow<EvalUpdate> = _rawEvalFlow.sample(THROTTLE_MS)

    /** Default throttle window in milliseconds. */
    const val THROTTLE_MS: Long = 120L

    /**
     * Per-move bounds flow — push-based replacement for polling.
     *
     * Each emission carries the current engine depth + node count alongside
     * per-move score bounds, so the 3-line overlay always has fresh metadata.
     */
    private val _rawBoundsFlow = MutableSharedFlow<List<MoveBound>>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val boundsFlow: Flow<List<MoveBound>> = _rawBoundsFlow.sample(THROTTLE_MS)

    /** Whether the native bridge has been initialised. */
    private var initialized = false

    // ── Public API ───────────────────────────────────────────────

    /**
     * One-time initialisation. Idempotent.
     *
     * Reads the JVM pointer from JNI so the native observer can call back
     * into Kotlin from arbitrary POSIX threads.
     */
    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = nativeContInit()
        }
    }

    /**
     * Install the Kotlin-side callback that receives raw JSON from the
     * native observer.  After this call, `evalFlow` will emit on every
     * search update.
     *
     * This must be called once before [startContinuousSearch].
     */
    fun registerCallback() {
        nativeRegisterCallback(SearchUpdateCallback())
    }

    /**
     * Launch a continuous search.
     *
     * The search runs entirely on a native POSIX thread; this call returns
     * immediately.  Live updates appear on [evalFlow].
     *
     * Only one search may be active at a time. Calling this while another
     * search is running will stop the previous one first.
     *
     * @param board      66-character Edax board string
     * @param level      search strength (1–60)
     * @param moveTimeMs time budget in ms; 0 = search until manually stopped
     * @return true if the search was started successfully
     */
    fun startContinuousSearch(
        board: String,
        level: Int = 15,
        moveTimeMs: Long = 0L
    ): Boolean {
        if (!initialized) return false
        // Stop any existing search cleanly.
        stopSearch()
        // Invalidate any in-flight callbacks from the previous search.
        searchEpoch++
        return nativeStartContinuousSearch(board, level, moveTimeMs)
    }

    /**
     * Request a graceful stop of the running search.
     *
     * The engine will exit at the next check point (typically within
     * a few milliseconds).  The final update is emitted on [evalFlow]
     * before the search terminates.
     */
    fun stopSearch() {
        nativeStopSearch()
    }

    /** Whether a search is currently in progress on the native side. */
    val isSearchRunning: Boolean
        get() = nativeIsSearchRunning()

    /**
     * Dynamically change the engine's parallel worker count.
     *
     * This is the primary integration point for [ThermalThrottleManager]:
     *
     * - Thermal OK      → 4 threads (full power)
     * - Thermal WARNING  → 2 threads
     * - Thermal SEVERE   → 1 thread
     *
     * @param n desired thread count, clamped to [1 .. MAX_THREADS-1]
     */
    fun setThreadCount(n: Int) {
        nativeSetThreadCount(n)
    }

    /** Return the current engine thread count. */
    val threadCount: Int
        get() = nativeGetThreadCount()

    /**
     * Get the per-move evaluation bounds from the live search.
     *
     * Use this to populate the per-cell score matrix on the board UI.
     * Returns an empty list if no search is active or no bounds are available.
     */
    fun getMoveBounds(): List<MoveBound> {
        if (!initialized) return emptyList()
        return try {
            val json = nativeGetMoveBounds()
            val arr = JSONObject(json).optJSONArray("moves") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MoveBound(
                    move = obj.getString("move"),
                    lo   = obj.optInt("lo", Int.MIN_VALUE),
                    hi   = obj.optInt("hi", Int.MIN_VALUE)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Request an immediate snapshot from the engine.
     *
     * Useful after a configuration change so the UI doesn't need to wait
     * for the next depth iteration to get data.
     */
    fun requestSnapshot() {
        nativeRequestSnapshot()
    }

    // ── Internal callback ────────────────────────────────────────

    /**
     * Called from native code (via JNI) on the search worker thread.
     *
     * Parses the compact JSON emitted by [cont_observer] and pushes it
     * into [_rawEvalFlow] on [Dispatchers.Default] so the Flow pipeline
     * runs off the native thread.
     *
     * **Important:** This method is called from a POSIX thread that may
     * or may not be attached to the JVM. The JNI layer handles attachment.
     * We must be fast and never throw.
     */
    @Suppress("unused") // called from JNI
    internal class SearchUpdateCallback {
        fun onSearchUpdate(json: String) {
            val capturedEpoch = searchEpoch
            // Log raw engine output for debugging missing evaluations
            if (json.length <= 200) {
                Log.d("EdaxRawOutput", "[observer] $json")
            } else {
                Log.d("EdaxRawOutput", "[observer] ${json.take(180)}… (${json.length} chars)")
            }
            try {
                val obj = JSONObject(json)
                val type = obj.optString("type", "")
                when (type) {
                    "bounds" -> {
                        val arr = obj.optJSONArray("moves")
                        if (arr == null) {
                            Log.w("EdaxRawOutput", "[observer] bounds type but no 'moves' array")
                            return
                        }
                        val depth = obj.optInt("d", 0)
                        val nodes = obj.optLong("n", 0L)
                        val bounds = (0 until arr.length()).map { i ->
                            val m = arr.getJSONObject(i)
                            MoveBound(
                                move    = m.getString("x"),
                                lo      = m.optInt("lo", Int.MIN_VALUE),
                                hi      = m.optInt("hi", Int.MIN_VALUE),
                                depth   = depth,
                                nodes   = nodes,
                                isPv    = m.optBoolean("pv", false),
                                discard = m.optBoolean("discard", false)
                            )
                        }
                        Log.d("EdaxRawOutput", "[observer] parsed ${bounds.size} PV bounds at depth=$depth " +
                            "(pvMoves=${bounds.filter { it.isPv }.map { it.move }})")
                        if (capturedEpoch == searchEpoch && bounds.isNotEmpty()) {
                            _rawBoundsFlow.tryEmit(bounds)
                        }
                    }
                    else -> {
                        val update = EvalUpdate(
                            depth    = obj.optInt("d", 0),
                            score    = obj.optInt("s", 0),
                            nodes    = obj.optLong("n", 0L),
                            timeMs   = obj.optLong("t", 0L),
                            bestMove = obj.optString("m", ""),
                            movesLeft = obj.optInt("l", 0)
                        )
                        if (capturedEpoch == searchEpoch) {
                            _rawEvalFlow.tryEmit(update)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("EdaxRawOutput", "[observer] failed to parse JSON: ${e.message}", e)
            }
        }
    }
}
