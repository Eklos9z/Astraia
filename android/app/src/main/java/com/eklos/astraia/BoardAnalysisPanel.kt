package com.eklos.astraia

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────
//  Color scheme (shared type so the Canvas lambda can resolve fields)
// ─────────────────────────────────────────────────────────────────

data class BoardColors(
    val bg: Color, val txt: Color, val txtDim: Color, val accent: Color,
    val boardTop: Color, val boardBot: Color, val grid: Color, val border: Color,
    val blackInner: Color, val blackOuter: Color,
    val whiteInner: Color, val whiteOuter: Color,
    val hintCircle: Color, val hintText: Color, val legalDot: Color,
    val thermalWarn: Color, val thermalSev: Color
)

private val LightColors = BoardColors(
    bg         = Color(0xFFf0ece4), txt        = Color(0xFF1a1a2e),
    txtDim     = Color(0xFF666680), accent     = Color(0xFFc89630),
    boardTop   = Color(0xFF2a8a5e), boardBot   = Color(0xFF1e7048),
    grid       = Color(0xFF3da070), border     = Color(0xFF1a5a3a),
    blackInner = Color(0xFF1a1a2e), blackOuter = Color(0xFF333340),
    whiteInner = Color(0xFFfaf8f0), whiteOuter = Color(0xFFc0beb8),
    hintCircle = Color(0x88c89630), hintText   = Color(0xFFc89630),
    legalDot   = Color(0x88666666),
    thermalWarn= Color(0xFFe8a030), thermalSev = Color(0xFFe04040)
)

private val DarkColors = BoardColors(
    bg         = Color(0xFF1a1a2e), txt        = Color(0xFFe0dcd0),
    txtDim     = Color(0xFF7a7a8a), accent     = Color(0xFFe8c170),
    boardTop   = Color(0xFF1a6b4a), boardBot   = Color(0xFF145535),
    grid       = Color(0xFF2d8a5e), border     = Color(0xFF0f3f28),
    blackInner = Color(0xFF2a2a3a), blackOuter = Color(0xFF444458),
    whiteInner = Color(0xFFf8f6f0), whiteOuter = Color(0xFFc8c6c0),
    hintCircle = Color(0x99e8c170), hintText   = Color(0xFFe8c170),
    legalDot   = Color(0x88999999),
    thermalWarn= Color(0xFFf0c040), thermalSev = Color(0xFFf06060)
)

// ─────────────────────────────────────────────────────────────────
//  Score formatters
// ─────────────────────────────────────────────────────────────────

/**
 * Convert Edax score to a human-readable stone difference.
 *
 * Edax scores are integers in [-64, +64] representing the expected
 * disc advantage at endgame (NOT centi-discs).
 *
 * When lo == hi the score is exact; otherwise a range is shown.
 *
 * Examples: +4 → "+4", -12 → "−12", 0 → "±0"
 */
private fun formatStoneDiff(lo: Int, hi: Int): String {
    return if (lo == hi) {
        when {
            lo > 0  -> "+$lo"
            lo < 0  -> "−${-lo}"   // Unicode MINUS SIGN
            else    -> "±0"
        }
    } else {
        "${lo}…${hi}"
    }
}

private fun formatNodes(nodes: Long): String = when {
    nodes >= 1_000_000_000L -> "%.1fG".format(nodes / 1_000_000_000.0)
    nodes >= 1_000_000L     -> "%.1fM".format(nodes / 1_000_000.0)
    nodes >= 1_000L         -> "%dK".format(nodes / 1_000)
    else                    -> nodes.toString()
}

// ─────────────────────────────────────────────────────────────────
//  Main composable
// ─────────────────────────────────────────────────────────────────

@Composable
fun BoardAnalysisPanel(
    state: AnalysisUiState,
    isLightTheme: Boolean = true,
    onMoveSelected: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val pal: BoardColors = if (isLightTheme) LightColors else DarkColors

    // Derive stable data — avoids recomposition when the board hasn't changed.
    val boardChars by remember(state.boardString) {
        derivedStateOf { state.boardString.toCharArray() }
    }
    val moveBoundMap by remember(state.moveBounds) {
        derivedStateOf { state.moveBounds.associateBy { it.move } }
    }
    val legalSet by remember(state.legalMoves) {
        derivedStateOf { state.legalMoves }
    }

    // ── Thermal status ───────────────────────────────────────
    if (state.thermalStatus != ThermalThrottleManager.ThermalLevel.OK) {
        ThermalStatusBar(
            status = state.engineStatusText,
            level  = state.thermalStatus,
            pal    = pal
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth().aspectRatio(1f).padding(top = 4.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (onMoveSelected != null) {
                        Modifier.pointerInput(state.boardString, state.legalMoves) {
                            detectTapGestures { tapOffset ->
                                val w = size.width
                                val h = size.height
                                val s = kotlin.math.min(w, h)
                                val axisW = s * 0.06f
                                val axisH = s * 0.05f
                                val pad = s * 0.01f
                                val boardSide = s - axisW - axisH - pad * 2f
                                val bx = axisH + pad
                                val cell = boardSide / 8f

                                val x = tapOffset.x - bx
                                val y = tapOffset.y
                                if (x < 0 || y < 0 || x > boardSide || y > boardSide) return@detectTapGestures
                                val col = (x / cell).toInt()
                                val row = (y / cell).toInt()
                                if (col !in 0..7 || row !in 0..7) return@detectTapGestures
                                val move = screenIdxToMove(row * 8 + col)
                                if (move in state.legalMoves) onMoveSelected(move)
                            }
                        }
                    } else Modifier
                )
        ) {
            val w = size.width
            val h = size.height
            val s = kotlin.math.min(w, h)
            val axisW = s * 0.06f
            val axisH = s * 0.05f
            val pad   = s * 0.01f
            val boardSide = s - axisW - axisH - pad * 2f
            val bx = axisH + pad
            val by = 0f
            val cell = boardSide / 8f
            val discR = cell * 0.40f

            // ── Board background ──────────────────────────
            drawRoundRect(
                color = pal.boardTop,
                topLeft = Offset(bx, by),
                size = Size(boardSide, boardSide),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )

            // ── Grid lines ────────────────────────────────
            val gridW = 0.8.dp.toPx()
            for (i in 0..8) {
                val l = bx + i * cell
                drawLine(pal.grid, Offset(l, by), Offset(l, by + boardSide), gridW)
                drawLine(pal.grid, Offset(bx, by + i * cell), Offset(bx + boardSide, by + i * cell), gridW)
            }

            // ── Discs & score overlay ─────────────────────
            for (si in 0 until 64) {
                val row = si / 8
                val col = si % 8
                val ei = row * 8 + col          // Edax index: rank-major (a1=0, b1=1, ..., h1=7, a2=8, ...)
                val cx = bx + col * cell + cell * 0.5f
                val cy = by + row * cell + cell * 0.5f

                val disc = if (ei < state.boardString.length) state.boardString[ei] else '-'

                when (disc) {
                    'X', 'O' -> {
                        drawCircle(
                            color = if (disc == 'X') pal.blackInner else pal.whiteInner,
                            radius = discR,
                            center = Offset(cx, cy)
                        )
                        drawCircle(
                            color = if (disc == 'X') pal.blackOuter else pal.whiteOuter,
                            radius = discR,
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.2.dp.toPx())
                        )
                    }
                    else -> {
                        val moveName = screenIdxToMove(si)
                        if (moveName in legalSet) {
                            val bound = moveBoundMap[moveName]
                            if (bound != null && bound.lo > Int.MIN_VALUE) {
                                drawCircle(
                                    color = pal.hintCircle,
                                    radius = discR * 0.88f,
                                    center = Offset(cx, cy)
                                )
                                drawScoreMatrix(
                                    pal = pal, cell = cell,
                                    cx = cx, cy = cy,
                                    lo = bound.lo,
                                    hi = bound.hi,
                                    depth = bound.depth,
                                    nodes = bound.nodes
                                )
                            } else if (state.showUmigame) {
                                // Umigame placeholder: show "N/A" overlay
                                drawCircle(
                                    color = pal.hintCircle,
                                    radius = discR * 0.88f,
                                    center = Offset(cx, cy)
                                )
                                drawUmigamePlaceholder(pal, cell, cx, cy)
                            } else {
                                drawCircle(
                                    color = pal.legalDot,
                                    radius = discR * 0.28f,
                                    center = Offset(cx, cy)
                                )
                            }
                        }
                    }
                }
            }

            // ── Coordinate axes (X-axis: a-h left-to-right, Y-axis: 1-8 top-to-bottom) ──────
            drawContext.canvas.nativeCanvas.let { canvas ->
                val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isLightTheme) 0x88000000.toInt() else 0x55ffffff.toInt()
                    textSize = cell * 0.26f
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                for (i in 0 until 8) {
                    // X-axis (horizontal, below board): a b c d e f g h
                    canvas.drawText(
                        ('a' + i).toString(),
                        bx + i * cell + cell * 0.5f,
                        by + boardSide + axisW * 0.64f,
                        textPaint
                    )
                    // Y-axis (vertical, left of board): 1 2 3 4 5 6 7 8
                    canvas.drawText(
                        (i + 1).toString(),
                        bx - axisH * 0.64f,
                        by + i * cell + cell * 0.60f,
                        textPaint
                    )
                }
            }

            // ── Board border ────────────────────────────────
            drawRoundRect(
                color = pal.border,
                topLeft = Offset(bx, by),
                size = Size(boardSide, boardSide),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Score-matrix text overlay (3-line high-density format)
// ─────────────────────────────────────────────────────────────────

/**
 * Draw a three-line analysis overlay inside a legal-move cell.
 *
 * ┌──────────────┐
 * │     +4       │  Line 1 — stone diff (bold, accent color, cell*0.26f)
 * │    D:23      │  Line 2 — depth confidence (normal, dim, cell*0.14f)
 * │   175M       │  Line 3 — node count (normal, dim, cell*0.14f)
 * └──────────────┘
 *
 * @param lo  lower bound of the per-move score (lo==hi means exact)
 * @param hi  upper bound of the per-move score
 */
private fun DrawScope.drawScoreMatrix(
    pal: BoardColors,
    cell: Float,
    cx: Float, cy: Float,
    lo: Int,
    hi: Int,
    depth: Int,
    nodes: Long
) {
    drawContext.canvas.nativeCanvas.let { canvas ->
        // Line 1: stone difference (larger, bold, accent)
        val scorePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.hintText.toArgb()
            textSize = cell * 0.26f
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(formatStoneDiff(lo, hi), cx, cy - cell * 0.16f, scorePaint)

        // Line 2: depth / confidence level (small, dim)
        val depthPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.txtDim.toArgb()
            textSize = cell * 0.14f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText("D:$depth", cx, cy + cell * 0.07f, depthPaint)

        // Line 3: node count (small, dim)
        val nodesPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.txtDim.toArgb()
            textSize = cell * 0.14f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText(formatNodes(nodes), cx, cy + cell * 0.21f, nodesPaint)
    }
}

/**
 * Placeholder overlay for Umigame Number mode.
 *
 * Displays "N/A" in the cell until the engine supports win-count lookups.
 */
private fun DrawScope.drawUmigamePlaceholder(
    pal: BoardColors,
    cell: Float,
    cx: Float,
    cy: Float
) {
    drawContext.canvas.nativeCanvas.let { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.txtDim.toArgb()
            textSize = cell * 0.20f
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("N/A", cx, cy + cell * 0.06f, paint)
    }
}

// ─────────────────────────────────────────────────────────────────
//  Thermal status banner
// ─────────────────────────────────────────────────────────────────

@Composable
private fun ThermalStatusBar(
    status: String,
    level: ThermalThrottleManager.ThermalLevel,
    pal: BoardColors
) {
    val (bg, fg) = when (level) {
        ThermalThrottleManager.ThermalLevel.WARMING  -> pal.thermalWarn to pal.thermalWarn
        ThermalThrottleManager.ThermalLevel.SEVERE   -> pal.thermalSev  to pal.thermalSev
        ThermalThrottleManager.ThermalLevel.CRITICAL -> pal.thermalSev  to pal.thermalSev
        else -> Color.Transparent to Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = when (level) {
                ThermalThrottleManager.ThermalLevel.WARMING  -> "🌡️ $status"
                ThermalThrottleManager.ThermalLevel.SEVERE   -> "🔥 $status"
                ThermalThrottleManager.ThermalLevel.CRITICAL -> "⚠️ $status"
                else -> status
            },
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  Coordinate helpers
// ─────────────────────────────────────────────────────────────────

/**
 * Convert a screen index (0-63, row-major: top-left → bottom-right)
 * into standard Othello algebraic notation.
 *
 * Screen layout (Othello convention: rank 1 = top, rank 8 = bottom):
 *   a1  b1  c1  d1  e1  f1  g1  h1    ← top row
 *   a2  b2  c2  d2  e2  f2  g2  h2
 *   ...
 *   a8  b8  c8  d8  e8  f8  g8  h8    ← bottom row
 *
 * @param si  screen index (0 = top-left, 63 = bottom-right)
 * @return algebraic coordinate like "f5"
 */
private fun screenIdxToMove(si: Int): String {
    val row = si / 8   // 0=top → 7=bottom
    val col = si % 8   // 0=left → 7=right
    return "${('a' + col)}${('1' + row)}"
}

@Suppress("unused")
private fun moveToEdaxIdx(move: String): Int {
    if (move.length < 2) return -1
    return (move[1] - '1') * 8 + (move[0] - 'a')
}

/** Convert engine score (discs) to a display string. */
fun EvalUpdate.stoneDiff(): String {
    val s = score
    return when {
        s > 0  -> "+$s"
        s < 0  -> "$s"
        else   -> "±0"
    }
}
