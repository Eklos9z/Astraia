package com.eklos.astraia;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public final class EdaxEngine {
    static {
        System.loadLibrary("astraia_jni");
    }

    private EdaxEngine() {
    }

    public static String prepareEvalFile(Context context) {
        File dataDir = new File(context.getFilesDir(), "data");
        File evalFile = new File(dataDir, "eval.dat");
        if (evalFile.isFile() && evalFile.length() > 0) {
            return evalFile.getAbsolutePath();
        }

        if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
            return null;
        }

        try (InputStream in = context.getAssets().open("data/eval.dat");
             FileOutputStream out = new FileOutputStream(evalFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return evalFile.getAbsolutePath();
        } catch (IOException ignored) {
            return null;
        }
    }

    public static String prepareBookFile(Context context) {
        File dataDir = new File(context.getFilesDir(), "data");
        File bookFile = new File(dataDir, "book.dat");
        if (bookFile.isFile() && bookFile.length() > 0) {
            return bookFile.getAbsolutePath();
        }

        if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
            return null;
        }

        try (InputStream in = context.getAssets().open("data/book.dat");
             FileOutputStream out = new FileOutputStream(bookFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return bookFile.getAbsolutePath();
        } catch (IOException ignored) {
            return null;
        }
    }

    // === Initialization ===

    public static String initialize(String evalPath, int hashBits, int threads) {
        return nativeInitialize(evalPath == null ? "" : evalPath, hashBits, threads);
    }

    public static String loadBook(String bookPath) {
        if (bookPath == null || bookPath.isEmpty()) return "ERROR: no book path";
        return nativeBookLoad(bookPath);
    }

    public static void setBookEnabled(boolean enabled) {
        nativeSetBookEnabled(enabled);
    }

    // === Board operations ===

    public static String initialBoard() {
        return nativeInitialBoard();
    }

    public static Set<String> legalMoves(String board) {
        String csv = nativeLegalMoves(board);
        Set<String> moves = new LinkedHashSet<>();
        if (csv == null || csv.isEmpty()) return moves;
        for (String move : csv.split(",")) {
            if (!move.isEmpty()) moves.add(move);
        }
        return moves;
    }

    public static String playMove(String board, String move) {
        return nativePlayMove(board, move);
    }

    public static boolean isGameOver(String board) {
        return nativeIsGameOver(board);
    }

    public static String undo(String board, String move) {
        return nativeUndo(board, move);
    }

    // === Search & Analysis ===

    public static String analyze(String board, int level, int moveTimeMs) {
        return nativeAnalyze(board, level, moveTimeMs);
    }

    /** Get the best N moves with scores for display (hint feature) */
    public static List<HintMove> hint(String board, int level, int count) {
        String json = nativeHint(board, level, count);
        List<HintMove> result = new ArrayList<>();
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.optBoolean("ok")) {
                JSONArray moves = obj.optJSONArray("moves");
                if (moves != null) {
                    for (int i = 0; i < moves.length(); i++) {
                        JSONObject m = moves.getJSONObject(i);
                        result.add(new HintMove(
                            m.getString("move"),
                            m.getInt("score"),
                            m.getInt("depth"),
                            m.optString("pv", "")
                        ));
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    // === Game info ===

    public static GameInfo getGameInfo(String board) {
        String json = nativeGameInfo(board);
        try {
            JSONObject obj = new JSONObject(json);
            return new GameInfo(
                obj.getInt("blackDiscs"),
                obj.getInt("whiteDiscs"),
                obj.getInt("empties"),
                obj.optBoolean("gameOver"),
                obj.optString("winner", "none")
            );
        } catch (Exception ignored) {
            return new GameInfo(0, 0, 64, false, "none");
        }
    }

    // === Book ===

    /** Get book move if available, empty string if none */
    public static String bookMove(String board) {
        return nativeBookMove(board);
    }

    // === Time control ===

    /** Set play type: 0=level, 1=time-per-game, 2=time-per-move */
    public static void setPlayType(int playType, long timeSec) {
        nativeSetPlayType(playType, timeSec);
    }

    // === Shutdown ===

    public static void shutdown() {
        nativeShutdown();
    }

    // === Data classes ===

    public static class HintMove {
        public final String move;
        public final int score;
        public final int depth;
        public final String pv;

        HintMove(String move, int score, int depth, String pv) {
            this.move = move;
            this.score = score;
            this.depth = depth;
            this.pv = pv;
        }
    }

    public static class GameInfo {
        public final int blackDiscs;
        public final int whiteDiscs;
        public final int empties;
        public final boolean gameOver;
        public final String winner;

        GameInfo(int black, int white, int empty, boolean over, String winner) {
            this.blackDiscs = black;
            this.whiteDiscs = white;
            this.empties = empty;
            this.gameOver = over;
            this.winner = winner;
        }
    }

    // === Native declarations ===

    private static native String nativeInitialize(String evalPath, int hashBits, int threads);
    private static native String nativeInitialBoard();
    private static native String nativeLegalMoves(String board);
    private static native String nativePlayMove(String board, String move);
    private static native boolean nativeIsGameOver(String board);
    private static native String nativeAnalyze(String board, int level, int moveTimeMs);
    private static native void nativeShutdown();

    // New
    private static native String nativeHint(String board, int level, int count);
    private static native String nativeGameInfo(String board);
    private static native String nativeUndo(String board, String move);
    private static native void nativeSetPlayType(int playType, long timeSec);
    private static native void nativeSetPondering(boolean enable);
    private static native String nativeBookLoad(String path);
    private static native String nativeBookMove(String board);
    private static native void nativeSetBookEnabled(boolean enable);
}