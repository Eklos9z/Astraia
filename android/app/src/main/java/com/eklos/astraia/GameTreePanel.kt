package com.eklos.astraia

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Scrollable game tree panel showing the move history.
 *
 * Each row displays:
 * - Move number (1-based, alternating black/white)
 * - The coordinate played (e.g. "f5")
 * - Score at time of move (if available)
 * - Visual highlight for the current position
 *
 * Tapping a row jumps the board to that historical state.
 */
@Composable
fun GameTreePanel(
    gameNodes: List<GameNode>,
    currentNodeIndex: Int,
    onJumpToState: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Section header
        Surface(
            onClick = { expanded = !expanded },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Game Tree (${gameNodes.size} moves)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    if (expanded) "▾" else "▸",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (expanded && gameNodes.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(gameNodes) { index, node ->
                    val isCurrent = node.id == currentNodeIndex
                    val moveNumber = index + 1
                    val isBlack = moveNumber % 2 == 1

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                else Color.Transparent
                            )
                            .clickable { onJumpToState(node.id) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Move number badge
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isBlack) Color(0xFF1a1a2e) else Color(0xFFf0ece4)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$moveNumber",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = if (isBlack) Color.White else Color(0xFF1a1a2e)
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        // Move coordinate
                        Text(
                            text = node.move ?: "—",
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(Modifier.weight(1f))

                        // Score (if available)
                        if (node.score != null) {
                            val stoneDiff = node.score / 100.0
                            val scoreText = if (stoneDiff >= 0) "+%.1f".format(stoneDiff)
                                            else "%.1f".format(stoneDiff)
                            Text(
                                text = scoreText,
                                fontSize = 12.sp,
                                color = if (stoneDiff >= 0) Color(0xFF2a8a5e) else Color(0xFFc04040)
                            )
                        }
                    }
                }
            }
        } else if (expanded) {
            Text(
                "No moves yet. Tap a legal square on the board to start.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
