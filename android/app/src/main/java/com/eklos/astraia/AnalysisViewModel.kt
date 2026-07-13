package com.eklos.astraia

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

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
 * @property searchLevel    current AI search depth (1-60)
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
    val searchLevel: Int = 15,
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

    // ── Search coordination ─────────────────────────────────────
    /** Monotonic generation counter so stale results are discarded. */
    private val searchGeneration = AtomicInteger(0)
    /** Debounce job for search-level slider changes. */
    private var searchLevelJob: Job? = null

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
        // Only accept bounds from the continuous search when they provide
        // truly distinct per-move scores (more than one unique lo/hi value),
        // otherwise keep any existing per-move hint data.
        viewModelScope.launch {
            EdaxContinuousBridge.boundsFlow.collect { bounds ->
                if (!_uiState.value.isThinking) return@collect

                // ── PV-aware merge + asymmetric discard ─────────
                // 1. PV moves: update score/nodes with exact deep-search data.
                // 2. Discard moves: remove from display (hopelessly behind PV).
                // 3. Non-PV moves: keep existing hint evaluations untouched.
                val existing = _uiState.value.moveBounds.associateBy { it.move }.toMutableMap()
                var changed = false
                for (b in bounds) {
                    if (b.discard) {
                        // Engine pruned this move — remove from the UI.
                        if (existing.remove(b.move) != null) {
                            changed = true
                            Log.d("EdaxRawOutput", "[bounds] discard removed: ${b.move} (too far behind PV)")
                        }
                    } else if (b.isPv) {
                        val old = existing[b.move]
                        if (old == null || old.depth < b.depth || old.lo != b.lo || old.nodes != b.nodes) {
                            existing[b.move] = b
                            changed = true
                        }
                    } else if (b.move !in existing) {
                        existing[b.move] = b
                    }
                }
                if (changed || _uiState.value.moveBounds.isEmpty()) {
                    val merged = existing.values.toList()
                    val pvSample = bounds.filter { it.isPv }.joinToString(", ") { "${it.move}=${it.lo}@D${it.depth}" }
                    val discSample = bounds.filter { it.discard }.joinToString(", ") { it.move }
                    if (discSample.isNotEmpty()) {
                        Log.d("EdaxRawOutput", "[bounds] discard: [$discSample] — total kept: ${merged.size}")
                    }
                    Log.d("EdaxRawOutput", "[bounds] PV-merge: total ${merged.size} moves, updated PV: [$pvSample]")
                    _uiState.update { it.copy(moveBounds = merged) }
                }
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

        val gen = searchGeneration.incrementAndGet()

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

        // ── Sign convention log ──────────────────────────────
        // Edax scores are always from the perspective of the SIDE TO MOVE.
        // Positive = advantage for current player, Negative = disadvantage.
        // The UI displays this directly: "+4" means "current player ahead by 4 discs",
        // "−4" means "current player behind by 4 discs".
        val sideChar = if (board.length >= 66) board[65] else '?'
        val sideName = when (sideChar) { 'X' -> "Black" ; 'O' -> "White" ; else -> "?" }
        Log.d("EdaxRawOutput", "[sign] board=${board.take(65)}... side=$sideName — scores are $sideName's perspective (+ good, − bad)")

        // ── Per-move evaluation via hint ──────────────────────────
        // Run independent per-move searches at moderate depth so each
        // legal cell displays its own distinct evaluation, not a
        // shared global best score.
        if (legalMoves.isNotEmpty() && !legalMoves.contains("pass")) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val hintLevel = minOf(level, 20)  // moderate cap for quick initial feedback;
                                                     // the continuous search observer now provides
                                                     // progressively deeper per-move bounds at every
                                                     // depth iteration (via force_observer flag)
                    val hints = EdaxEngine.hint(board, hintLevel, legalMoves.size)
                    Log.d("EdaxRawOutput", "[hint] requested ${legalMoves.size} moves at level $hintLevel, got ${hints.size} results")
                    if (hints.size < legalMoves.size) {
                        Log.w("EdaxRawOutput", "[hint] MISSING: ${legalMoves.size - hints.size} legal moves have no evaluation!")
                        val evalMoves = hints.map { it.move }.toSet()
                        val missing = legalMoves.filter { it !in evalMoves && it != "pass" }
                        if (missing.isNotEmpty()) {
                            Log.w("EdaxRawOutput", "[hint] unevaluated moves: $missing")
                        }
                    }
                    // Coordinate trace: log first few evaluations for verification
                    if (hints.isNotEmpty()) {
                        val sample = hints.take(5).joinToString(", ") { "${it.move}=${it.score}@D${it.depth}" }
                        val sideChar = if (board.length >= 66) board[65] else '?'
                        Log.d("EdaxRawOutput", "[hint] sample evals (${board.length} char board, side=$sideChar): [$sample ...]")
                    }
                    if (hints.isNotEmpty()
                        && searchGeneration.get() == gen
                        && _uiState.value.boardString == board
                    ) {
                        val bounds = hints.map { hint ->
                            MoveBound(
                                move = hint.move,
                                lo = hint.score,
                                hi = hint.score,
                                depth = hint.depth,
                                nodes = hint.nodes
                            )
                        }
                        _uiState.update { it.copy(moveBounds = bounds) }
                    }
                } catch (e: Exception) {
                    Log.e("EdaxRawOutput", "[hint] exception: ${e.message}", e)
                }
            }
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

    /**
     * Set the AI search level (1–60).
     *
     * Updates the state immediately for responsive UI feedback, then
     * restarts the analysis with a short debounce so rapid slider
     * dragging doesn't flood the engine with restarts.
     */
    fun setSearchLevel(level: Int) {
        val clamped = level.coerceIn(1, 60)
        _uiState.update { it.copy(searchLevel = clamped) }

        searchLevelJob?.cancel()
        searchLevelJob = viewModelScope.launch {
            delay(300L)  // debounce: wait for the user to finish dragging
            val board = _uiState.value.boardString
            if (board.length == 66) {
                startAnalysis(board, level = clamped, legalMoves = _uiState.value.legalMoves)
            }
        }
    }

    // ── Public API: Game State ──────────────────────────────────

    /**
     * Resolve any pass that is required at the given board position.
     *
     * If the next player has no legal moves but the game is not over,
     * applies a pass move and appends it to the game history.
     *
     * @return Pair of (finalBoard, finalLegalMoves)
     */
    private fun resolvePass(board: String): Pair<String, Set<String>> {
        var currentBoard = board
        var legalMoves = if (!EdaxEngine.isGameOver(currentBoard))
            EdaxEngine.legalMoves(currentBoard) else emptySet()

        if (legalMoves.size == 1 && legalMoves.contains("pass")) {
            val passBoard = EdaxEngine.playMove(currentBoard, "pass")
            if (passBoard != null && passBoard.length == 66) {
                val passNode = GameNode(
                    id = ++gameNodeCounter,
                    board = passBoard,
                    move = "pass",
                    score = null
                )
                _gameNodes.add(passNode)
                currentBoard = passBoard
                legalMoves = if (!EdaxEngine.isGameOver(currentBoard))
                    EdaxEngine.legalMoves(currentBoard) else emptySet()
            }
        }
        return Pair(currentBoard, legalMoves)
    }

    /**
     * Play a move on the current board. Returns the final board string or null on failure.
     *
     * Passes are resolved inline (before starting analysis) to avoid the race
     * condition that occurred when [playMove] recursively called itself,
     * causing two native search threads to overlap briefly.
     */
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

        // ── Resolve pass BEFORE starting analysis ────────────────
        // This avoids the old recursive call which triggered a second
        // startAnalysis → stopAnalysis → startContinuousSearch in
        // rapid succession, causing native search threads to race.
        val (finalBoard, finalLegalMoves) = resolvePass(nextBoard)

        _uiState.update { it.copy(
            boardString = finalBoard,
            legalMoves = finalLegalMoves,
            gameNodes = _gameNodes.toList(),
            currentNodeIndex = _gameNodes.last().id
        )}

        val level = _uiState.value.searchLevel
        startAnalysis(finalBoard, level = level, legalMoves = finalLegalMoves)

        return finalBoard
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

        // Trim history after this node (for now; branching will preserve alternatives)
        val idx = _gameNodes.indexOfFirst { it.id == nodeIndex }
        if (idx >= 0) {
            while (_gameNodes.size > idx + 1) _gameNodes.removeAt(_gameNodes.size - 1)
        }

        // ── Resolve pass BEFORE starting analysis ────────────────
        val (finalBoard, finalLegalMoves) = resolvePass(targetBoard)

        _uiState.update { it.copy(
            boardString = finalBoard,
            legalMoves = finalLegalMoves,
            gameNodes = _gameNodes.toList(),
            currentNodeIndex = _gameNodes.lastOrNull()?.id ?: nodeIndex,
            moveBounds = emptyList(),
            latestUpdate = null
        )}

        val level = _uiState.value.searchLevel
        startAnalysis(finalBoard, level = level, legalMoves = finalLegalMoves)

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

        // ── Resolve pass BEFORE starting analysis ────────────────
        val (finalBoard, finalLegalMoves) = resolvePass(prevBoard)

        _uiState.update { it.copy(
            boardString = finalBoard,
            legalMoves = finalLegalMoves,
            gameNodes = _gameNodes.toList(),
            currentNodeIndex = _gameNodes.lastOrNull()?.id ?: -1,
            moveBounds = emptyList(),
            latestUpdate = null
        )}

        val level = _uiState.value.searchLevel
        startAnalysis(finalBoard, level = level, legalMoves = finalLegalMoves)

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

        val level = _uiState.value.searchLevel

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

        startAnalysis(board, level = level, legalMoves = legalMoves)
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
        searchLevelJob?.cancel()
        stopAnalysis()
        thermalManager.shutdown()
    }
}
