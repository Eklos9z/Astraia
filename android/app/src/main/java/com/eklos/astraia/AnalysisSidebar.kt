package com.eklos.astraia

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Professional analysis sidebar combining:
 * - Kifu import section
 * - Game tree (move history)
 * - Kifu export section
 * - Umigame Number toggle
 * - Basic controls (undo, new game)
 */
@Composable
fun AnalysisSidebar(
    gameNodes: List<GameNode>,
    currentNodeIndex: Int,
    showUmigame: Boolean,
    onKifuImport: (String) -> Int,
    onExportKifu: () -> String,
    onJumpToState: (Int) -> Unit,
    onToggleUmigame: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onNewGame: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Section 1: Kifu Import ─────────────────────────
        KifuImportSection(onImport = onKifuImport)

        // ── Section 2: Game Tree ───────────────────────────
        GameTreePanel(
            gameNodes = gameNodes,
            currentNodeIndex = currentNodeIndex,
            onJumpToState = onJumpToState
        )

        // ── Section 3: Kifu Export ─────────────────────────
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Kifu Export",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                if (gameNodes.isNotEmpty()) {
                    val preview = gameNodes.mapNotNull { it.move }.joinToString("")
                    Text(
                        text = if (preview.length > 40) preview.take(40) + "…" else preview,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Button(
                    onClick = {
                        val kifuText = onExportKifu()
                        if (kifuText.isNotEmpty()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Othello Kifu", kifuText))
                            Toast.makeText(context, "棋谱已复制", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "No moves to export", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = gameNodes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export to Clipboard", fontSize = 13.sp)
                }
            }
        }

        // ── Section 4: Umigame Toggle ──────────────────────
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Umigame Number (海亀数)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Switch(
                    checked = showUmigame,
                    onCheckedChange = onToggleUmigame
                )
            }
        }
        if (showUmigame) {
            Text(
                "Showing win-count continuations per legal move. " +
                "Computation deferred — showing N/A until tablebase integration.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // ── Section 5: Quick Controls ──────────────────────
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(
                    onClick = onUndo,
                    enabled = gameNodes.isNotEmpty(),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Undo", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onNewGame,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("New Game", fontSize = 12.sp)
                }
            }
        }
    }
}
