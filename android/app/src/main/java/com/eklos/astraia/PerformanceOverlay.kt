package com.eklos.astraia

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlin.math.*

/**
 * Collapsible real-time performance monitoring overlay for the analysis screen.
 *
 * ## Layout
 *
 * ```
 * ┌─────────────────────────────────────────┐
 * │ ⚡ Performance  ▲ (collapse)    [OFF]   │  <- header bar
 * ├─────────────────────────────────────────┤
 * │ CPU ████████░░░░  45%                   │  <- aggregate CPU bar
 * │  0  ██████████░░  62%                   │  <- per-core bars
 * │  1  ████░░░░░░░░  28%                   │
 * │  ...                                    │
 * │ NPS ▕▁▂▃▄▅▆▇█▇▆▅▄▃  1.2M n/s          │  <- NPS line chart
 * │ ⚠ Thermal severe — threads reduced      │  <- thermal warning (conditional)
 * └─────────────────────────────────────────┘
 * ```
 *
 * ## Drawing Strategy
 *
 * - CPU bars use [drawRect] with a lerp'd color per bar.
 * - NPS chart uses [drawPath] with a polyline path cached via [drawWithCache].
 * - The NPS Y-axis is logarithmic (base 10), clamped to a minimum of 100 n/s
 *   so that idle periods (NPS = 0) render as a flat bottom line.
 *
 * @param perfFlow    stream of [PerfSnapshot] from [PerformanceStreamUseCase].
 * @param thermalLevel current thermal status for the warning bar.
 * @param isExpanded  whether the detail panel is visible.
 * @param onToggleExpand  called when the header is tapped.
 * @param enabled     master toggle — when false, the overlay is hidden entirely.
 * @param onToggleEnabled  called when the ON/OFF switch is flipped.
 */
@Composable
fun PerformanceOverlay(
    perfFlow: Flow<PerfSnapshot>,
    thermalLevel: ThermalThrottleManager.ThermalLevel,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // ── Ring buffer: last 60 seconds of perf data ──────────────
    // Always collect, even when disabled — so enabling shows live data instantly.
    val history = remember { mutableStateListOf<PerfSnapshot>() }
    val maxHistory = remember { 300 } // 60s / 200ms

    // Use Unit key (not perfFlow) — stable across recompositions.
    LaunchedEffect(Unit) {
        perfFlow.collect { snap ->
            history.add(snap)
            if (history.size > maxHistory) {
                history.removeRange(0, history.size - maxHistory)
            }
        }
    }

    val latest = history.lastOrNull()
    val colors = MaterialTheme.colorScheme

    // Always render — the panel itself never disappears.
    // When `!enabled`, the header is minimal ("⚡ Perf OFF ▶").
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surfaceVariant.copy(alpha = 0.85f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // ── Header (always visible) ────────────────────────
            PerfHeader(
                isExpanded = isExpanded,
                onToggleExpand = onToggleExpand,
                enabled = enabled,
                onToggleEnabled = onToggleEnabled,
                latestNps = latest?.nps ?: 0L,
                latestCpu = latest?.cpuPercent ?: 0f
            )

            // ── Detail panel: only when enabled AND expanded ───
            AnimatedVisibility(
                visible = enabled && isExpanded,
                enter = expandVertically(),
                exit  = shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // CPU section
                    if (latest != null) {
                        CpuSection(
                            cpuPercent = latest.cpuPercent,
                            corePercents = latest.corePercents
                        )
                    }

                    // NPS chart section
                    if (history.isNotEmpty()) {
                        NpsChartSection(history = history.toList())
                    }

                    // Thermal warning
                    if (thermalLevel >= ThermalThrottleManager.ThermalLevel.WARMING) {
                        ThermalWarningBar(thermalLevel)
                    }
                }
            }

            // ── When disabled but expanded: show the switch ────
            AnimatedVisibility(
                visible = !enabled && isExpanded,
                enter = expandVertically(),
                exit  = shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Performance monitoring is OFF. Toggle to enable:",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = false,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.height(20.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Header row
// ─────────────────────────────────────────────────────────────────

@Composable
private fun PerfHeader(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    latestNps: Long,
    latestCpu: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: expand toggle + label + (if enabled) live values
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isExpanded) "▼" else "▶",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (enabled) "⚡ Perf" else "⚡ Perf OFF",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (enabled) {
                Text(
                    text = "CPU ${latestCpu.toInt()}%",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = cpuColor(latestCpu)
                )
                Text(
                    text = "NPS ${formatNpsShort(latestNps)}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Right: ON/OFF toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (enabled) "ON" else "OFF",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Color(0xFF4caf50) else Color(0xFF888888)
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.height(20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  CPU bars section
// ─────────────────────────────────────────────────────────────────

@Composable
private fun CpuSection(
    cpuPercent: Float,
    corePercents: List<Float>
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "CPU",
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Aggregate bar
        CpuBar(
            label = "All",
            percent = cpuPercent,
            modifier = Modifier.fillMaxWidth().height(14.dp)
        )

        // Per-core bars
        if (corePercents.size <= 16) {
            corePercents.forEachIndexed { i, pct ->
                CpuBar(
                    label = "$i",
                    percent = pct,
                    modifier = Modifier.fillMaxWidth().height(10.dp)
                )
            }
        } else {
            // Too many cores — summarise
            Text(
                text = "${corePercents.size} cores, avg ${
                    if (corePercents.isNotEmpty()) "%.0f".format(corePercents.average()) else "—"
                }%",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CpuBar(
    label: String,
    percent: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Label
        Text(
            text = label,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp)
        )

        // Bar drawn via Canvas with drawWithCache for path caching.
        // The background track is static (size-dependent); the fill
        // changes with `percent` and is drawn over it.
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .drawWithCache {
                    val trackColor = Color(0x22000000)
                    val cornerPx = 2.dp.toPx()

                    onDrawBehind {
                        val w = size.width
                        val h = size.height
                        val barW = w * (percent / 100f).coerceIn(0f, 1f)
                        val barColor = cpuColor(percent)

                        // Background track (cached)
                        drawRoundRect(
                            color = trackColor,
                            size = Size(w, h),
                            cornerRadius = CornerRadius(cornerPx, cornerPx)
                        )

                        // Filled bar (data-dependent — drawn every frame)
                        if (barW > 0f) {
                            drawRoundRect(
                                color = barColor,
                                size = Size(barW, h),
                                cornerRadius = CornerRadius(cornerPx, cornerPx)
                            )
                        }
                    }
                }
        ) { /* drawing handled by drawWithCache */ }

        // Percentage text
        Text(
            text = "${percent.toInt()}%",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = cpuColor(percent),
            modifier = Modifier.width(30.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  NPS line chart
// ─────────────────────────────────────────────────────────────────

@Composable
private fun NpsChartSection(history: List<PerfSnapshot>) {
    // Persist Path objects across recompositions to avoid allocation churn.
    val linePath = remember { Path() }
    val fillPath = remember { Path() }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "NPS (nodes/sec, 60s)",
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        val chartHeight = 80.dp

        // ── drawWithCache caches grid-line commands (size-dependent, not data-dependent)
        //     while the live NPS polyline is rebuilt from `remember`-ed Path objects
        //     and drawn inside onDrawBehind every frame. ────────────────
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .drawWithCache {
                    // --- Cache builder: recomputes only when Canvas size changes ---
                    val gridColor = Color(0x12ffffff)
                    val gridStroke = 0.5.dp.toPx()
                    val lineColor = Color(0xFF4caf50)
                    val fillColor = Color(0x184caf50)
                    val lineStroke = 1.5.dp.toPx()
                    val pad = 2.dp.toPx()

                    // Pre-compute grid-line Y positions once per size.
                    // The NPS log-scale mapping is a pure function of canvas height.
                    val logMin = 2.0   // 10² = 100 n/s
                    val logMax = 7.0   // 10⁷ = 10M n/s
                    val logRange = logMax - logMin
                    val gridYs = (2..7).map { decade ->
                        val logNps = decade.toDouble()
                        val frac = ((logNps - logMin) / logRange).toFloat()
                        this@drawWithCache.size.height - pad -
                            (this@drawWithCache.size.height - pad * 2f) * frac
                    }

                    onDrawBehind {
                        // Decade grid lines (cached — computed once, redrawn every frame).
                        val w = size.width
                        gridYs.forEach { y ->
                            drawLine(gridColor, Offset(0f, y), Offset(w, y), gridStroke)
                        }

                        // Live data paths — rebuilt each frame via `remember`-ed Paths
                        // that are reset & populated inside the Canvas content lambda.
                        if (!fillPath.isEmpty) {
                            drawPath(fillPath, fillColor)
                        }
                        if (!linePath.isEmpty) {
                            drawPath(
                                linePath, lineColor,
                                style = Stroke(
                                    width = lineStroke,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }
        ) {
            // Canvas content lambda: rebuild data paths every draw pass.
            // Uses `remember`-ed Path objects (.reset() + rebuild) to avoid GC pressure.
            val padPx = 2.dp.toPx()  // compute in density-aware scope
            if (history.size >= 2) {
                buildNpsPaths(
                    history = history,
                    w = size.width,
                    h = size.height,
                    pad = padPx,
                    linePath = linePath,
                    fillPath = fillPath
                )
            }
        }

        // Y-axis tick labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("100", "1K", "10K", "100K", "1M", "10M").forEach { label ->
                Text(
                    text = label,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Build (or rebuild) the NPS polyline + fill-under paths into pre-allocated
 * [Path] objects.  Call this inside the Canvas content lambda so it has access
 * to the final layout size.
 *
 * Uses `.reset()` on each [Path] to avoid allocations — the same two [Path]
 * instances are reused for the lifetime of the composition.
 */
private fun buildNpsPaths(
    history: List<PerfSnapshot>,
    w: Float,
    h: Float,
    pad: Float,
    linePath: Path,
    fillPath: Path
) {
    val logMin = 2.0
    val logMax = 7.0
    val logRange = logMax - logMin

    fun npsToY(nps: Long): Float {
        if (nps <= 0L) return h - pad
        val logNps = ln(nps.toDouble()) / ln(10.0)
        val clamped = logNps.coerceIn(logMin, logMax)
        return h - pad - (h - pad * 2f) * ((clamped - logMin) / logRange).toFloat()
    }

    linePath.reset()
    fillPath.reset()

    val stepX = w / (history.size - 1).coerceAtLeast(1)

    history.forEachIndexed { i, snap ->
        val x = i * stepX
        val y = npsToY(snap.nps)
        if (i == 0) {
            linePath.moveTo(x, y)
            fillPath.moveTo(x, y)
        } else {
            linePath.lineTo(x, y)
            fillPath.lineTo(x, y)
        }
    }

    // Close the fill under the curve.
    fillPath.lineTo((history.size - 1) * stepX, h - pad)
    fillPath.lineTo(0f, h - pad)
    fillPath.close()
}

// ─────────────────────────────────────────────────────────────────
//  Thermal warning bar
// ─────────────────────────────────────────────────────────────────

@Composable
private fun ThermalWarningBar(level: ThermalThrottleManager.ThermalLevel) {
    val bg: Color
    val fg: Color
    val icon: String
    val text: String
    when (level) {
        ThermalThrottleManager.ThermalLevel.WARMING -> {
            bg = Color(0x22e8a030); fg = Color(0xFFe8a030)
            icon = "🌡️"; text = "Device warming — threads reduced to 2"
        }
        ThermalThrottleManager.ThermalLevel.SEVERE -> {
            bg = Color(0x33e04040); fg = Color(0xFFe04040)
            icon = "🔥"; text = "Thermal severe — single thread"
        }
        ThermalThrottleManager.ThermalLevel.CRITICAL -> {
            bg = Color(0x44e04040); fg = Color(0xFFff4040)
            icon = "⚠️"; text = "Thermal critical — search paused"
        }
        else -> return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = icon, fontSize = 12.sp)
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = fg
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  Formatting helpers
// ─────────────────────────────────────────────────────────────────

/**
 * CPU utilisation → a color on the green → yellow → red gradient.
 *
 * 0% → green, 50% → yellow, 100% → red.
 */
private fun cpuColor(percent: Float): Color {
    val t = (percent / 100f).coerceIn(0f, 1f)
    // Green (0, 200, 80) → Yellow (240, 200, 0) → Red (240, 60, 40)
    return if (t < 0.5f) {
        val s = t * 2f
        Color(
            red   = lerp(0f, 240f, s) / 255f,
            green = 200f / 255f,
            blue  = lerp(80f, 0f, s) / 255f
        )
    } else {
        val s = (t - 0.5f) * 2f
        Color(
            red   = 240f / 255f,
            green = lerp(200f, 60f, s) / 255f,
            blue  = lerp(0f, 40f, s) / 255f
        )
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * Short human-readable NPS format for the header.
 *
 * Examples: 0, 850, 1.2K, 3.5M, 1.8G
 */
private fun formatNpsShort(nps: Long): String = when {
    nps >= 1_000_000_000L -> "%.1fG".format(nps / 1_000_000_000.0)
    nps >= 1_000_000L     -> "%.1fM".format(nps / 1_000_000.0)
    nps >= 1_000L         -> "%.1fK".format(nps / 1_000.0)
    nps > 0L              -> nps.toString()
    else                  -> "0"
}

/**
 * Raise a [Double] to an integer power.
 */
private fun Double.pow(exp: Int): Double {
    var result = 1.0
    var base = this
    var e = exp
    while (e > 0) {
        if (e and 1 == 1) result *= base
        base *= base
        e = e shr 1
    }
    return result
}
