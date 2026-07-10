package com.eklos.astraia

/**
 * A node in the game tree.
 *
 * @property id        unique node identifier (monotonically increasing)
 * @property board     66-char Edax board string at this state
 * @property move      the move that led to this state (e.g. "f5", "pass"; null for root)
 * @property score     engine score at time of move (may be null)
 * @property annotation user-editable free-text note for this position
 */
data class GameNode(
    val id: Int,
    val board: String,
    val move: String?,
    val score: Int? = null,
    val annotation: String = ""
)
