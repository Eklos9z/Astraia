# Astraia Android App

This directory is a minimal Android Studio project that wraps the Edax C engine (astraia_jni)
through JNI.

## Build

1. Install Android Studio with the NDK and CMake components.
2. Download Edax evaluation weights from an Edax release and extract `eval.dat`.
3. Copy it to `android/app/src/main/assets/data/eval.dat`.
4. Open this `android/` directory in Android Studio and run the `app` target.

The app still opens without `eval.dat`, but native AI search is disabled until
the file is present.

## Native Boundary

JNI calls are intentionally small:

- `initialBoard()`
- `legalMoves(board)`
- `playMove(board, move)`
- `analyze(board, level, moveTimeMs)`

The board format is Edax's compact 66-character string: 64 board squares,
a space, then the side to move (`X` or `O`).
