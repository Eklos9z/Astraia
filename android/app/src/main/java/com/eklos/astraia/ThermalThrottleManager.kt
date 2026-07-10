package com.eklos.astraia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors Android system thermal & battery state and translates it into
 * a simple four-level [ThermalLevel] consumed by [AnalysisViewModel].
 *
 * ## Integration
 *
 * ```kotlin
 * // In AnalysisViewModel.init:
 * thermalManager.thermalState.collect { level ->
 *     val threads = when (level) {
 *         OK       -> 4
 *         WARMING  -> 2
 *         SEVERE   -> 1
 *         CRITICAL -> 1  // + stop search
 *     }
 *     EdaxContinuousBridge.setThreadCount(threads)
 * }
 * ```
 *
 * ## API Level Support
 *
 * - API 29+ (Android 10): uses [PowerManager.addThermalStatusListener]
 *   for real-time thermal callbacks.
 * - API 28   (minSdk): falls back to battery-only monitoring; thermal
 *   status is inferred from battery temperature via sticky broadcast.
 *
 * ## Thermal Level Mapping
 *
 * | Android Thermal Status   | ThermalLevel | Action                         |
 * |--------------------------|--------------|--------------------------------|
 * | THERMAL_STATUS_NONE      | OK           | Full power (4 threads)          |
 * | THERMAL_STATUS_LIGHT     | WARMING      | Reduce to 2 threads             |
 * | THERMAL_STATUS_MODERATE  | WARMING      | Reduce to 2 threads             |
 * | THERMAL_STATUS_SEVERE    | SEVERE       | Reduce to 1 thread              |
 * | THERMAL_STATUS_CRITICAL  | CRITICAL     | Single thread + pause search    |
 * | THERMAL_STATUS_EMERGENCY | CRITICAL     | Single thread + pause search    |
 */
class ThermalThrottleManager private constructor(
    private val context: Context
) {
    // ── Public thermal level enum ───────────────────────────────

    enum class ThermalLevel {
        /** Normal operating temperature. */
        OK,
        /** Device is warming; reduce CPU to avoid throttling later. */
        WARMING,
        /** Device is hot; aggressive CPU reduction needed. */
        SEVERE,
        /** Device is overheating; pause computation entirely. */
        CRITICAL
    }

    // ── Observable state ────────────────────────────────────────

    private val _thermalState = MutableStateFlow(ThermalLevel.OK)
    val thermalState: StateFlow<ThermalLevel> = _thermalState.asStateFlow()

    // ── Internal state ──────────────────────────────────────────

    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var isLowBattery: Boolean = false
    private var currentAndroidThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE

    /**
     * On API 29+, this listener receives real-time status changes.
     * We keep a reference so we can unregister it.
     */
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    // ── Battery receiver ────────────────────────────────────────

    /**
     * Listens for ACTION_BATTERY_CHANGED to detect low-battery mode
     * and read the battery temperature as a fallback thermal signal.
     */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0f

            val pct = if (scale > 0) (level * 100.0f / scale) else 100.0f
            val wasLowBattery = isLowBattery
            isLowBattery = (pct < LOW_BATTERY_PCT && plugged == 0)

            // If no thermal API is available, use battery temperature.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val battLevel = when {
                    tempC >= BATTERY_TEMP_CRITICAL -> ThermalLevel.CRITICAL
                    tempC >= BATTERY_TEMP_SEVERE   -> ThermalLevel.SEVERE
                    tempC >= BATTERY_TEMP_WARMING  -> ThermalLevel.WARMING
                    else                           -> ThermalLevel.OK
                }
                if (battLevel != _thermalState.value || wasLowBattery != isLowBattery) {
                    recomputeThermalLevel(battLevel)
                }
            } else if (wasLowBattery != isLowBattery) {
                recomputeThermalLevel()
            }
        }
    }

    // ── Initialisation ──────────────────────────────────────────

    init {
        // Register battery receiver.
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        // Register thermal listener (API 29+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registerThermalListenerApi29()
        }

        // Read initial thermal state.
        recomputeThermalLevel()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerThermalListenerApi29() {
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            currentAndroidThermalStatus = status
            recomputeThermalLevel()
        }
        thermalListener = listener
        powerManager.addThermalStatusListener(
            { command -> command.run() },
            listener
        )
    }

    // ── Level computation ───────────────────────────────────────

    /**
     * Compute the effective [ThermalLevel] from the Android thermal status
     * and battery state, then emit it via [_thermalState].
     */
    private fun recomputeThermalLevel(
        batteryFallbackLevel: ThermalLevel = ThermalLevel.OK
    ) {
        val thermalLevel: ThermalLevel

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use the real thermal API.
            thermalLevel = mapAndroidThermalStatus(currentAndroidThermalStatus)
        } else {
            // Fall back to battery temperature.
            thermalLevel = batteryFallbackLevel
        }

        // Low battery + not charging always forces at least WARMING.
        val effective = when {
            isLowBattery && thermalLevel < ThermalLevel.WARMING -> ThermalLevel.WARMING
            else -> thermalLevel
        }

        if (effective != _thermalState.value) {
            _thermalState.value = effective
        }
    }

    /**
     * Map Android's 6 thermal statuses into our 4-level system.
     */
    private fun mapAndroidThermalStatus(status: Int): ThermalLevel = when (status) {
        PowerManager.THERMAL_STATUS_NONE     -> ThermalLevel.OK
        PowerManager.THERMAL_STATUS_LIGHT    -> ThermalLevel.WARMING
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.WARMING
        PowerManager.THERMAL_STATUS_SEVERE   -> ThermalLevel.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY-> ThermalLevel.CRITICAL
        else                                 -> ThermalLevel.OK
    }

    // ── Cleanup ─────────────────────────────────────────────────

    /**
     * Must be called when the application is shutting down to unregister
     * listeners and receivers.
     */
    fun shutdown() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered.
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let {
                powerManager.removeThermalStatusListener(it)
            }
            thermalListener = null
        }
    }

    // ── Companion ───────────────────────────────────────────────

    companion object {
        /** Battery percentage below which we consider "low battery" (not charging). */
        private const val LOW_BATTERY_PCT = 15.0f

        /** Battery temperature thresholds in °C (fallback for API < 29). */
        private const val BATTERY_TEMP_WARMING  = 38.0f
        private const val BATTERY_TEMP_SEVERE   = 43.0f
        private const val BATTERY_TEMP_CRITICAL = 48.0f

        @Volatile
        private var instance: ThermalThrottleManager? = null

        /**
         * Get or create the singleton [ThermalThrottleManager].
         *
         * @param context application context (not an Activity context).
         */
        fun getInstance(context: Context): ThermalThrottleManager {
            return instance ?: synchronized(this) {
                instance ?: ThermalThrottleManager(
                    context.applicationContext
                ).also { instance = it }
            }
        }
    }
}
