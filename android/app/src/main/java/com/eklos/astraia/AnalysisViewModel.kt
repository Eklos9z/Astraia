package com.eklos.astraia

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Aggregate state pushed to the Compose UI on every engine update.
 *
 * @property latestUpdate   the most recent per-depth evaluation
 * @property moveBounds     per-move score bounds from the live search
 * @property boardString    66-char Edax board representation
 * @property legalMoves     set of legal moves in algebraic notation
 * @property isThinking     true while a search is in progress
 * @property thermalStatus  current thermal level for UI badge
 * @property threadCount    active engine worker threads
 * @property engineStatusText  human-readable engine / thermal status
 * @property gameNodes      move history for the game tree panel
 * @property currentNodeIndex  position in move history
 * @property showUmigame    whether Umigame number overlay is active
 * @property statusMessage  transient snackbar / toast message
 */
data class AnalysisUiState(
    val latestUpdate: EvalUpdate? = null,
    val moveBounds: List<MoveBound> = emptyList(),
    val boardString: String = "",
    val legalMoves: Set<String> = emptySet(),
    val isThinking: Boolean = false,
    val thermalStatus: ThermalThrottleManager.ThermalLevel = ThermalThrottleManager.ThermalLevel.OK,
    val threadCount: Int = 4,
    val engineStatusText: String = "",
    val gameNodes: List<GameNode> = emptyList(),
    val currentNodeIndex: Int = -1,
    val showUmigame: Boolean = false,
    val statusMessage: String? = null
)

/**
 * ViewModel that drives the continuous-analysis Compose UI.
 *
 * ## Responsibilities
 *
 * 1. Exposes a single [uiState] [StateFlow] the UI collects once.
 * 2. Starts/stops continuous search in response to board changes.
 * 3. Collects per-move bounds via push-based [EdaxContinuousBridge.boundsFlow].
 * 4. Integrates [ThermalThrottleManager] so the UI can display thermal status.
 * 5. Manages game state: move history, undo, kifu import, Umigame toggle.
 */
class AnalysisViewModel(application: Application) : AndroidViewModel(application) {

    // ── Thermal integration ─────────────────────────────────────
    private val thermalManager = ThermalThrottleManager.getInstance(application)

    // ── Game state ──────────────────────────────────────────────
    private var gameNodeCounter = 0
    private val _gameNodes = mutableListOf<GameNode>()

    // ── Compose-friendly state ──────────────────────────────────
    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    init {
        // One-time initialisation of the native bridge.
        EdaxContinuousBridge.init()
        EdaxContinuousBridge.registerCallback()

        // Pipe throttled eval updates into the UI state.
        viewModelScope.launch {
            EdaxContinuousBridge.evalFlow.collect { update ->
                _uiState.update { it.copy(latestUpdate = update, isThinking = true) }
            }
        }

        // Pipe push-based per-move bounds (NO MORE POLLING).
        viewModelScope.launch {
            EdaxContinuousBridge.boundsFlow.collect { bounds ->
                _uiState.update { it.copy(moveBounds = bounds) }
            }
        }

        // Observe thermal state changes.
        viewModelScope.launch {
            thermalManager.thermalState.collect { level ->
                val threads = when (level) {
                    ThermalThrottleManager.ThermalLevel.OK       -> 4
                    ThermalThrottleManager.ThermalLevel.WARMING  -> 2
                    ThermalThrottleManager.ThermalLevel.SEVERE   -> 1
                    ThermalThrottleManager.ThermalLevel.CRITICAL -> 1
                }
                EdaxContinuousBridge.setThreadCount(threads)
                _uiState.update {
                    it.copy(
                        thermalStatus = level,
                        threadCount = threads,
                        engineStatusText = when (level) {
                            ThermalThrottleManager.ThermalLevel.OK       -> ""
                            ThermalThrottleManager.ThermalLevel.WARMING  -> "Device warming — reduced to 2 threads"
                            ThermalThrottleManager.ThermalLevel.SEVERE   -> "Thermal severe — single thread"
                            ThermalThrottleManager.ThermalLevel.CRITICAL -> "Thermal critical — search paused"
                        }
                    )
                }

                // On CRITICAL, also stop the search entirely.
                if (level == ThermalThrottleManager.ThermalLevel.CRITICAL) {
                    stopAnalysis()
                }
            }
        }
    }

    // ── Public API: Analysis ────────────────────────────────────

    /**
     * Start continuous analysis of a given board position.
     *
     * @param board       66-char Edax board string
     * @param level       search strength
     * @param legalMoves  currently legal moves (for UI display)
     */
    fun startAnalysis(board: String, level: Int = 15, legalMoves: Set<String> = emptySet()) {
        stopAnalysis()

        _uiState.update { it.copy(
            boardString = board,
            legalMoves = legalMoves,
            isThinking = true,
            moveBounds = emptyList(),
            latestUpdate = null,
            engineStatusText = "Thinking..."
        )}

        val started = EdaxContinuousBridge.startContinuousSearch(board, level, moveTimeMs = 0L)
        if (!started) {
            _uiState.update { it.copy(isThinking = false, engineStatusText = "Engine unavailable") }
        }
    }

    /** Stop analysis gracefully. */
    fun stopAnalysis() {
        EdaxContinuousBridge.stopSearch()
        val finalBounds = EdaxContinuousBridge.getMoveBounds()
        _uiState.update { it.copy(
            isThinking = false,
            moveBounds = if (finalBounds.isNotEmpty()) finalBounds else it.moveBounds,
            engineStatusText = ""
        )}
    }

    /** Request an immediate snapshot (e.g. after config change). */
    fun requestSnapshot() {
        EdaxContinuousBridge.requestSnapshot()
    }

    // ── Public API: Game State ──────────────────────────────────

    /** Play a move on the current board. Returns the new board string or null on failure. */
    fun playMove(move: String): String? {
        val currentBoard = _uiState.value.boardString
        if (currentBoard.length != 66) return null

        val nextBoard = EdaxEngine.playMove(currentBoard, move)
        if (nextBoard == null || nextBoard.length != 66) return null

        // Push to history
        val node = GameNode(
            id = ++gameNodeCounter,
            board = nextBoard,
            move = move,
            score = _uiState.value.latestUpdate?.score
        )
        _gameNodes.add(node)

        val legalMoves = if (!EdaxEngine.isGameOver(nextBoard))
            EdaxEngine.legalMoves(nextBoard) else emptySet()

        _uiState.update { it.copy(
            boardString = nextBoard,
            legalMoves = legalMoves,
            gameNodes = _gameNodes.toList(),
            currentNodeIndex = node.id
        )}

        startAnalysis(nextBoard, legalMoves = legalMoves)
        return nextBoard
    }

    /** Play a batch of moves (from kifu import). */
    fun playSequence(moves: List<String>): List<String> {
        val applied = mutableListOf<String>()
        for (move in moves) {
            val result = playMove(move)
            if (result != null) applied.add(move) else break
        }
        val msg = if (applied.size == moves.size) {
            "${applied.size} moves imported"
        } else {
            "${applied.size}/${moves.size} moves imported (stopped at invalid move)"
        }
        _uiState.update { it.copy(statusMessage = msg) }
        return applied
    }

    /** Jump to a specific historical state by node index. */
    fun jumpToState(nodeIndex: Int): Boolean {
        val node = _gameNodes.find { it.id == nodeIndex } ?: return false

        // Rebuild board from history up to this node
        val targetBoard = node.board
        val legalMoves = if (!EdaxEngine.isGameOver(targetBoard))
            EdaxEngine.legalMoves(targetBoard) else emptySet()

        // Trim history after this node (for now; branching will preserve alternatives)
        val idx = _gameNodes.indexOfFirst { it.id == nodeIndex }
        if (idx >= 0) {
            while (_gameNodes.size > idx + 1) _gameNodes.removeAt(_gameNodes.size - 1)
        }

        _uiState.update { it.copy(
            boardString = targetBoard,
            legalMoves = legalMoves,
            gameNodes = _gameNodes.toList(),
            currentNodeIndex = nodeIndex,
            moveBounds = emptyList(),
            latestUpdate = null
        )}

        startAnalysis(targetBoard, legalMoves = legalMoves)
        return true
    }

    /** Undo the last move. */
    fun undoMove(): Boolean {
        if (_gameNodes.isEmpty()) return false
        _gameNodes.removeAt(_gameNodes.size - 1)

        val prevBoard = if (_gameNodes.isNotEmpty()) {
            _gameNodes.last().board
        } else {
            EdaxEngine.initialBoard()
        }

        val legalMoves = if (!EdaxEngine.isGameOver(prevBoard))
            EdaxEngine.legalMoves(prevBoard) else emptySet()

        _uiState.update { it.copy(
            boardString = prevBoard,
            legalMoves = legalMoves,
            gameNodes = _gameNodes.toList(),
            currentNodeIndex = _gameNodes.lastOrNull()?.id ?: -1,
            moveBounds = emptyList(),
            latestUpdate = null
        )}

        startAnalysis(prevBoard, legalMoves = legalMoves)
        return true
    }

    /** Import kifu from clipboard text. */
    fun importFromClipboard(text: String): Int {
        val moves = KifuParser.parseNotation(text)
        if (moves.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "No valid moves found in clipboard") }
            return 0
        }
        playSequence(moves)
        return moves.size
    }

    /** Toggle Umigame number overlay. */
    fun toggleUmigame(enabled: Boolean) {
        _uiState.update { it.copy(
            showUmigame = enabled,
            statusMessage = if (enabled) "Umigame Number overlay enabled" else null
        )}
    }

    /** Reset to a fresh game. */
    fun newGame() {
        stopAnalysis()
        _gameNodes.clear()
        gameNodeCounter = 0
        val board = EdaxEngine.initialBoard()
        val legalMoves = EdaxEngine.legalMoves(board)

        _uiState.update { it.copy(
            boardString = board,
            legalMoves = legalMoves,
            gameNodes = emptyList(),
            currentNodeIndex = -1,
            moveBounds = emptyList(),
            latestUpdate = null,
            isThinking = false,
            statusMessage = "New game"
        )}

        startAnalysis(board, legalMoves = legalMoves)
    }

    /** Export the current game tree as a compact kifu string. */
    fun exportKifu(): String {
        val moves = _gameNodes.mapNotNull { it.move }
        if (moves.isEmpty()) return ""
        return moves.joinToString("")  // "f5d6c3e2..."
    }

    /** Clear the status message after it's been shown. */
    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopAnalysis()
        thermalManager.shutdown()
    }
}
