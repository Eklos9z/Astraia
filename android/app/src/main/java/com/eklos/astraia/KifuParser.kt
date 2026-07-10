package com.eklos.astraia

/**
 * Utility for parsing standard Othello kifu / game notation.
 *
 * Supported input formats:
 * - Compact: "f5d6c3b4e2..."
 * - Spaced:  "f5 d6 c3 b4 e2 ..."
 * - With line breaks and mixed whitespace
 * - "pass" tokens (as the word, not "pa")
 *
 * The parser normalises case, strips punctuation, and extracts
 * consecutive coordinate pairs and "pass" tokens.
 */
object KifuParser {

    private val MOVE_REGEX = Regex("""([a-h][1-8]|pass)""")

    /**
     * Parse a raw kifu text string into an ordered list of normalised moves.
     *
     * Examples:
     * - "f5d6c3"   → ["f5", "d6", "c3"]
     * - "F5 D6 C3" → ["f5", "d6", "c3"]
     * - "f5, d6; pass; c3" → ["f5", "d6", "pass", "c3"]
     *
     * @param text  raw input (clipboard, OCR, etc.)
     * @return ordered list of lowercase move strings
     */
    fun parseNotation(text: String): List<String> {
        val lowered = text.trim().lowercase()
        return MOVE_REGEX.findAll(lowered)
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
}
