package com.eklos.astraia

/**
 * Utility for parsing standard Othello kifu / game notation.
 *
 * Supported input formats:
 * - Compact: "f5d6c3b4e2..."
 * - Spaced:  "f5 d6 c3 b4 e2 ..."
 * - Numbered: "1.f5 2.d6 3.c3 4.b4 5.e2"
 * - Othello Quest: any text containing (a-h)(1-8) coordinate pairs
 * - With line breaks, commas, semicolons, and mixed whitespace
 * - "pass" / "pa" pass tokens
 *
 * The parser normalises case, strips punctuation and move numbers,
 * and extracts consecutive coordinate pairs and pass tokens.
 */
object KifuParser {

    /**
     * Matches standard algebraic coordinates optionally preceded by a move
     * number and dot (e.g., "1.f5" or "12.d3"), plus the "pass" keyword.
     *
     * The regex is intentionally permissive — it extracts anything that looks
     * like a move and ignores everything else (game results, annotations, etc.).
     */
    private val MOVE_REGEX = Regex("""(?:\d+\.)?([a-h][1-8])|(?:\bpass\b)""")

    /** Direct coordinate-only regex for compact format (no prefixes). */
    private val COMPACT_REGEX = Regex("""([a-h][1-8]|pass)""")

    /**
     * Parse a raw kifu text string into an ordered list of normalised moves.
     *
     * Examples:
     * - "f5d6c3"            → ["f5", "d6", "c3"]
     * - "F5 D6 C3"          → ["f5", "d6", "c3"]
     * - "1.f5 2.d6 3.c3"    → ["f5", "d6", "c3"]
     * - "f5, d6; pass; c3"  → ["f5", "d6", "pass", "c3"]
     * - "f5 d6 (1-0)"       → ["f5", "d6"]
     *
     * @param text  raw input (clipboard, OCR, etc.)
     * @return ordered list of lowercase move strings
     */
    fun parseNotation(text: String): List<String> {
        val lowered = text.trim().lowercase()

        // Try numbered format first (e.g. "1.f5 2.d6")
        val numberedMatches = MOVE_REGEX.findAll(lowered).toList()
        if (numberedMatches.isNotEmpty()) {
            return numberedMatches.map { match ->
                // Group 1 captures the coordinate for numbered moves
                match.groupValues[1].ifEmpty { match.value }
            }.filter { it.isNotEmpty() }
        }

        // Fallback to compact/spaced format
        return COMPACT_REGEX.findAll(lowered)
            .map { it.value }
            .toList()
    }

    /**
     * Normalise a single move token to lowercase Edax format.
     *
     * "F5" → "f5", "D6" → "d6", "Pass" → "pass"
     */
    fun normalizeMove(move: String): String {
        val cleaned = move.trim().lowercase()
        return when {
            cleaned == "pass" || cleaned == "pa" -> "pass"
            cleaned.length >= 2 && cleaned[0] in 'a'..'h' && cleaned[1] in '1'..'8' ->
                cleaned.substring(0, 2)
            cleaned.length >= 2 && cleaned[1] in 'a'..'h' && cleaned[0] in '1'..'8' ->
                // Transposed: "5f" → "f5"
                "${cleaned[1]}${cleaned[0]}"
            else -> cleaned
        }
    }

    /**
     * Convert a list of moves back to compact kifu notation.
     *
     * ["f5", "d6", "c3"] → "f5d6c3"
     */
    fun toNotation(moves: List<String>): String = moves.joinToString("")
}
