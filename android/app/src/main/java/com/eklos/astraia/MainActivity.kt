package com.eklos.astraia

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Compose-based main Activity for the Astraia professional Othello analysis workbench.
 *
 * Replaces the old View-based [MainActivity.java] with a responsive Compose layout:
 * - Landscape: board (55%) | analysis sidebar (45%)
 * - Portrait:  board (full width) then sidebar below
 */
@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── One-time Edax engine initialisation ─────────────────
        val threads = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() / 2))
        val evalPath = EdaxEngine.prepareEvalFile(this)
        val initResult = EdaxEngine.initialize(evalPath, 18, threads)
        val engineReady = initResult != null && initResult.startsWith("OK")

        val bookPath = EdaxEngine.prepareBookFile(this)
        if (bookPath != null) {
            val r = EdaxEngine.loadBook(bookPath)
            if (r == "OK") EdaxEngine.setBookEnabled(true)
        }

        if (engineReady) {
            EdaxContinuousBridge.init()
            EdaxContinuousBridge.registerCallback()
        }

        setContent {
            val viewModel: AnalysisViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()
            var isLightTheme by remember { mutableStateOf(true) }

            // Show snackbar for status messages
            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(uiState.statusMessage) {
                uiState.statusMessage?.let { msg ->
                    snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
                    viewModel.clearStatusMessage()
                }
            }

            MaterialTheme(
                colorScheme = if (isLightTheme) lightColorScheme() else darkColorScheme()
            ) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    "Astraia",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            actions = {
                                // Theme toggle
                                IconButton(onClick = { isLightTheme = !isLightTheme }) {
                                    Icon(
                                        imageVector = if (isLightTheme) Icons.Outlined.DarkMode
                                                      else Icons.Outlined.LightMode,
                                        contentDescription = "Toggle theme",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                            )
                        )
                    }
                ) { paddingValues ->
                    AstraiaMainContent(
                        uiState = uiState,
                        isLightTheme = isLightTheme,
                        viewModel = viewModel,
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }

            // Start analysis on initial board
            LaunchedEffect(Unit) {
                if (engineReady && uiState.boardString.isEmpty()) {
                    val board = EdaxEngine.initialBoard()
                    val legalMoves = EdaxEngine.legalMoves(board)
                    viewModel.startAnalysis(board, level = 15, legalMoves = legalMoves)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EdaxEngine.shutdown()
    }
}

// ─────────────────────────────────────────────────────────────────
//  Responsive layout
// ─────────────────────────────────────────────────────────────────

@Composable
private fun AstraiaMainContent(
    uiState: AnalysisUiState,
    isLightTheme: Boolean,
    viewModel: AnalysisViewModel,
    modifier: Modifier = Modifier
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Board panel — 55% width
            BoardAnalysisPanel(
                state = uiState,
                isLightTheme = isLightTheme,
                onMoveSelected = { move -> viewModel.playMove(move) },
                modifier = Modifier.weight(0.55f)
            )

            // Analysis sidebar — 45% width
            AnalysisSidebar(
                gameNodes = uiState.gameNodes,
                currentNodeIndex = uiState.currentNodeIndex,
                showUmigame = uiState.showUmigame,
                onKifuImport = { text -> viewModel.importFromClipboard(text) },
                onJumpToState = { id -> viewModel.jumpToState(id) },
                onToggleUmigame = { enabled -> viewModel.toggleUmigame(enabled) },
                onUndo = { viewModel.undoMove() },
                onNewGame = { viewModel.newGame() },
                modifier = Modifier.weight(0.45f)
            )
        }
    } else {
        // Portrait: board on top, sidebar scrolls below
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BoardAnalysisPanel(
                state = uiState,
                isLightTheme = isLightTheme,
                onMoveSelected = { move -> viewModel.playMove(move) },
                modifier = Modifier.fillMaxWidth()
            )

            AnalysisSidebar(
                gameNodes = uiState.gameNodes,
                currentNodeIndex = uiState.currentNodeIndex,
                showUmigame = uiState.showUmigame,
                onKifuImport = { text -> viewModel.importFromClipboard(text) },
                onJumpToState = { id -> viewModel.jumpToState(id) },
                onToggleUmigame = { enabled -> viewModel.toggleUmigame(enabled) },
                onUndo = { viewModel.undoMove() },
                onNewGame = { viewModel.newGame() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
