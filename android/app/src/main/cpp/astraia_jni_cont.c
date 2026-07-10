/**
 * @file astraia_jni_cont.c
 *
 * @brief Continuous-evaluation JNI bridge for Edax on Android.
 *
 * Wires Edax's built-in `search_set_observer()` callback into the JVM so
 * Kotlin can receive live per-depth updates during iterative deepening.
 * The search runs entirely on a native POSIX thread — never on the JNI
 * caller thread — keeping the Android main thread free.
 *
 * ## Thread Model
 *
 * ┌─────────────────┐     ┌──────────────────────┐     ┌──────────────────┐
 * │  Kotlin caller   │────▶│  native worker thread │────▶│  YBWC pool       │
 * │  (any thread)    │     │  (search_run)         │     │  (n_task workers) │
 * └─────────────────┘     └──────────┬───────────┘     └──────────────────┘
 *                                    │ observer fires here
 *                                    ▼
 *                         ┌──────────────────────┐
 *                         │  JNI → Java callback │
 *                         │  (attached to JVM)   │
 *                         └──────────────────────┘
 *
 * @date 2026
 */

#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <inttypes.h>

#include "board.h"
#include "const.h"
#include "eval.h"
#include "move.h"
#include "options.h"
#include "search.h"
#include "util.h"

/* ------------------------------------------------------------------ */
/*  Forward declarations from astraia_jni.c                           */
/* ------------------------------------------------------------------ */
extern pthread_mutex_t engine_mutex;
extern bool eval_ready;
extern jstring new_string(JNIEnv*, const char*);
extern void square_to_coord(int, char*);
extern bool parse_board_text(const char*, Board*, int*, char*, size_t);

/* ------------------------------------------------------------------ */
/*  JVM / callback globals — guarded by observer_lock                 */
/* ------------------------------------------------------------------ */
static JavaVM        *g_jvm = NULL;
static pthread_mutex_t observer_lock = PTHREAD_MUTEX_INITIALIZER;
static jclass          g_callback_class;          /* global ref */
static jobject         g_callback_instance;       /* global ref — EdaxContinuousBridge singleton */
static jmethodID       g_callback_method;         /* onSearchUpdate(String json) */
static atomic_bool     g_observer_active = ATOMIC_VAR_INIT(false);
static atomic_intptr_t g_active_search_ptr = ATOMIC_VAR_INIT(0); /* Search* or NULL */

/* Per-search context for the worker thread */
typedef struct {
    Search  search;
    Board   board;
    int     player;
    int     level;
    int64_t move_time_ms;
    char    board_text[68];   /* original board string for board_to_string */
} ContSearchCtx;

/* ------------------------------------------------------------------ */
/*  Observer — called by Edax *inside* the search / YBWC threads      */
/* ------------------------------------------------------------------ */

/**
 * @brief Observer callback installed via search_set_observer().
 *
 * Edax calls this at the end of each completed depth iteration (and at
 * key score changes).  We serialise the current Result as a compact
 * JSON line and call back into the JVM.
 */
static void cont_observer(Result *result)
{
    if (!atomic_load(&g_observer_active)) return;

    /* Fast bail-out: if the JVM or callback isn't ready, skip. */
    JNIEnv *env = NULL;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        /* We might be on a native thread that hasn't attached yet. */
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
            return;
        }
    }

    /* Protect against concurrent observer install/teardown. */
    pthread_mutex_lock(&observer_lock);
    if (!atomic_load(&g_observer_active) || g_callback_instance == NULL) {
        pthread_mutex_unlock(&observer_lock);
        return;
    }

    /* Serialise Result → compact JSON.  Schema:
     * {
     *   "d":depth, "s":score, "n":nodes, "t":time_ms,
     *   "m":"bestmove", "l":n_moves_left
     * }
     */
    char move_str[8];
    square_to_coord(result->move, move_str);

    char json[4096];
    int written = snprintf(json, sizeof json,
        "{\"d\":%d,\"s\":%d,\"n\":%" PRIu64 ",\"t\":%" PRId64 ",\"m\":\"%s\",\"l\":%d}",
        result->depth,
        result->score,
        result->n_nodes,
        result->time,
        move_str,
        result->n_moves_left);

    if (written > 0 && (size_t)written < sizeof json) {
        jstring jjson = (*env)->NewStringUTF(env, json);
        if (jjson != NULL) {
            (*env)->CallVoidMethod(env, g_callback_instance, g_callback_method, jjson);
            (*env)->DeleteLocalRef(env, jjson);
        }
    }

    /* --- Emit per-move bounds as a second callback (NEW) ---
     * Schema: {"type":"bounds","d":depth,"n":nodes,"moves":[
     *   {"x":"f5","lo":2,"hi":6,"c":123}, ...]}
     */
    Search *active_search = (Search *)atomic_load(&g_active_search_ptr);
    if (active_search != NULL && !movelist_is_empty(&active_search->movelist)) {
        char bounds_json[4096];
        size_t used = 0;
        int bw = snprintf(bounds_json, sizeof bounds_json,
            "{\"type\":\"bounds\",\"d\":%d,\"n\":%" PRIu64 ",\"moves\":[",
            result->depth, result->n_nodes);
        if (bw > 0 && (size_t)bw < sizeof bounds_json) used = (size_t)bw;

        const Move *move;
        bool first = true;
        foreach_move(move, &active_search->movelist) {
            if (move->x < A1 || move->x > H8) continue;
            char coord[5];
            square_to_coord(move->x, coord);
            int lo = result->bound[move->x].lower;
            int hi = result->bound[move->x].upper;
            /* Signal unsearched moves by setting both to SCORE_MIN */
            if (lo == SCORE_MIN && hi == SCORE_MAX) {
                lo = SCORE_MIN; hi = SCORE_MIN;
            }
            bw = snprintf(bounds_json + used, sizeof bounds_json - used,
                "%s{\"x\":\"%s\",\"lo\":%d,\"hi\":%d,\"c\":%u}",
                first ? "" : ",", coord, lo, hi, move->cost);
            if (bw < 0 || (size_t)bw >= sizeof bounds_json - used) break;
            used += (size_t)bw;
            first = false;
        }

        bw = snprintf(bounds_json + used, sizeof bounds_json - used, "]}");
        if (bw > 0 && (size_t)bw < sizeof bounds_json - used) {
            used += (size_t)bw;
            jstring jbounds = (*env)->NewStringUTF(env, bounds_json);
            if (jbounds != NULL) {
                (*env)->CallVoidMethod(env, g_callback_instance, g_callback_method, jbounds);
                (*env)->DeleteLocalRef(env, jbounds);
            }
        }
    }

    /* The observer may fire very frequently (every depth iteration).
     * Flush pending exceptions silently — we must never crash the engine. */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    pthread_mutex_unlock(&observer_lock);
}

/* ------------------------------------------------------------------ */
/*  Search worker thread                                               */
/* ------------------------------------------------------------------ */

static void *cont_search_thread(void *arg)
{
    ContSearchCtx *ctx = (ContSearchCtx *)arg;

    pthread_mutex_lock(&engine_mutex);

    if (!eval_ready) {
        pthread_mutex_unlock(&engine_mutex);
        free(ctx);
        return NULL;
    }

    Search *search = &ctx->search;
    search_init(search);

    /* Install our observer so Kotlin gets live updates. */
    search_set_observer(search, cont_observer);

    search->options.verbosity = 0;   /* we drive output ourselves */
    search->options.depth = 60;

    search_set_board(search, &ctx->board, ctx->player);
    search_set_level(search, ctx->level, search->n_empties);

    if (ctx->move_time_ms > 0) {
        search_set_move_time(search, ctx->move_time_ms);
    }

    /* Store the active search pointer so external code can stop it. */
    atomic_store(&g_active_search_ptr, (intptr_t)search);

    pthread_mutex_unlock(&engine_mutex);

    /* ---- RUN THE SEARCH (blocking on this thread) ---- */
    search_run(search);
    /* ------------------------------------------------- */

    /* Final snapshot after search completes */
    cont_observer(search->result);

    atomic_store(&g_active_search_ptr, 0);
    search_free(search);
    free(ctx);
    return NULL;
}

/* ------------------------------------------------------------------ */
/*  JNI entry points                                                   */
/* ------------------------------------------------------------------ */

/**
 * Initialize the continuous-evaluation bridge.
 *
 * Must be called once before any other cont_* function.
 * Stores the JVM pointer for later use from native threads.
 *
 * @return JNI_TRUE on success.
 */
JNIEXPORT jboolean JNICALL
Java_com_eklos_astraia_EdaxContinuousBridge_nativeContInit(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    if ((*env)->GetJavaVM(env, &g_jvm) != JNI_OK) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/**
 * Register the Kotlin callback that receives live search updates.
 *
 * @param callback  an object implementing onSearchUpdate(String json)
 */
JNIEXPORT void JNICALL
Java_com_eklos_astraia_EdaxContinuousBridge_nativeRegisterCallback(
    JNIEnv *env, jclass clazz, jobject callback)
{
    (void)clazz;

    pthread_mutex_lock(&observer_lock);

    /* Release previous callback if any. */
    if (g_callback_instance != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback_instance);
        g_callback_instance = NULL;
    }
    if (g_callback_class != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback_class);
        g_callback_class = NULL;
    }
    g_callback_method = NULL;

    if (callback != NULL) {
        g_callback_instance = (*env)->NewGlobalRef(env, callback);
        jclass cls = (*env)->GetObjectClass(env, callback);
        g_callback_class = (jclass)(*env)->NewGlobalRef(env, cls);
        g_callback_method = (*env)->GetMethodID(env, cls, "onSearchUpdate", "(Ljava/lang/String;)V");
        if (g_callback_method == NULL) {
            (*env)->DeleteGlobalRef(env, g_callback_instance);
            (*env)->DeleteGlobalRef(env, g_callback_class);
            g_callback_instance = NULL;
            g_callback_class = NULL;
        }
    }

    pthread_mutex_unlock(&observer_lock);
}

/**
 * Start a continuous search on a dedicated native thread.
 *
 * The search will call the registered observer callback as it progresses
 * through iterative deepening.
 *
 * @param board_text  66-char Edax board string
 * @param level       search level (0-60)
 * @param move_time_ms maximum time in ms, or 0 for unlimited
 * @return JNI_TRUE if the search was launched.
 */
JNIEXPORT jboolean JNICALL
Java_com_eklos_astraia_EdaxContinuousBridge_nativeStartContinuousSearch(
    JNIEnv *env, jclass clazz, jstring board_text, jint level, jlong move_time_ms)
{
    (void)clazz;

    const char *text = (*env)->GetStringUTFChars(env, board_text, NULL);
    if (text == NULL) return JNI_FALSE;

    ContSearchCtx *ctx = (ContSearchCtx *)calloc(1, sizeof(ContSearchCtx));
    if (ctx == NULL) {
        (*env)->ReleaseStringUTFChars(env, board_text, text);
        return JNI_FALSE;
    }

    Board board;
    int player;
    char error[128];

    if (!parse_board_text(text, &board, &player, error, sizeof error)) {
        free(ctx);
        (*env)->ReleaseStringUTFChars(env, board_text, text);
        return JNI_FALSE;
    }

    ctx->board     = board;
    ctx->player    = player;
    ctx->level     = (level < 0) ? 0 : (level > 60) ? 60 : level;
    ctx->move_time_ms = (int64_t)move_time_ms;
    memcpy(ctx->board_text, text, 66);
    ctx->board_text[66] = '\0';

    (*env)->ReleaseStringUTFChars(env, board_text, text);

    /* Enable the observer callback. */
    atomic_store(&g_observer_active, true);

    /* Spawn the worker thread. */
    pthread_t tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);

    if (pthread_create(&tid, &attr, cont_search_thread, ctx) != 0) {
        atomic_store(&g_observer_active, false);
        free(ctx);
        pthread_attr_destroy(&attr);
        return JNI_FALSE;
    }

    pthread_attr_destroy(&attr);
    return JNI_TRUE;
}

/**
 * Stop the currently running continuous search (if any).
 *
 * Sends STOP_ON_DEMAND to all search threads so the engine exits
 * gracefully at the next check point.
 */
JNIEXPORT void JNICALL
Java_com_eklos_astraia_EdaxContinuousBridge_nativeStopSearch(
    JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;

    atomic_store(&g_observer_active, false);

    Search *search = (Search *)atomic_load(&g_active_search_ptr);
    if (search != NULL) {
        search_stop_all(search, STOP_ON_DEMAND);
    }
}

/**
 * Dynamically change the number of parallel search threads.
 *
 * This is the primary entry point for thermal throttling: when the device
 * heats up, the Kotlin layer can reduce n_task to 1 (or increase it back
 * when temperatures are safe).
 *
 * Changes take effect gradually — ongoing parallel sub-searches complete
 * before the new task count is fully reflected.
 *
 * @param n_threads  desired thread count (clamped to 1..MAX_THREADS-1)
 */
JNIEXPORT void JNICALL
Java_com_eklos_astraia_EdaxContinuousBridge_nativeSetThreadCount(
    JNIEnv *env, jclass clazz, jint n_threads)
{
    (void)env;
    (void)clazz;

    if (n_threads < 1) n_threads = 1;
    if (n_threads >= MAX_THREADS) n_threads = MAX_THREADS - 1;

    pthread_mutex_lock(&engine_mutex);
    options.n_task = n_threads;
    options_bound();

    /* If a search is running, propagate the new task count to it. */
    Search *search = (Search *)atomic_load(&g_active_search_ptr);
    if (search != NULL && search->master != NULL) {
        search_set_task_number(search->master, n_threads);
    }
    pthread_mutex_unlock(&engine_mutex);
}

/**
 * Get current engine thread count.
 */
JNIEXPORT jint JNICALL
Java_com_eklos_astraia_EdaxContinuousBridge_nativeGetThreadCount(
    JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return options.n_task;
}

/**
 * Get the per-move evaluation bounds (lower/upper score) from the last
 * search result.  This powers the per-cell score matrix on the UI.
 *
 * The returned JSON has the form:
 *   {"moves":[{"move":"f5","lo":-2,"hi":6},{"move":"d3","lo":-10,"hi":2}]}
 *
 * If no search is running or has completed, returns an empty result.
 */
JNIEXPORT jstring JNICALL
Java_com_eklos_astraia_EdaxContinuousBridge_nativeGetMoveBounds(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;

    Search *search = (Search *)atomic_load(&g_active_search_ptr);
    if (search == NULL) {
        return new_string(env, "{\"moves\":[]}");
    }

    Result *result = search->result;
    if (result == NULL) {
        return new_string(env, "{\"moves\":[]}");
    }

    char json[4096];
    size_t used = 0;
    int written;

    written = snprintf(json, sizeof json, "{\"moves\":[");
    if (written < 0) return new_string(env, "{\"moves\":[]}");
    used = (size_t)written;

    /* Iterate over the movelist if available. */
    if (!movelist_is_empty(&search->movelist)) {
        const Move *move;
        bool first = true;
        foreach_move(move, &search->movelist) {
            if (move->x < A1 || move->x > H8) continue;

            char coord[5];
            square_to_coord(move->x, coord);

            int lo = result->bound[move->x].lower;
            int hi = result->bound[move->x].upper;

            /* Skip moves that haven't been searched yet. */
            if (lo == SCORE_MIN && hi == SCORE_MAX) {
                lo = hi = SCORE_MIN; /* signal "unknown" */
            }

            written = snprintf(json + used, sizeof json - used,
                "%s{\"move\":\"%s\",\"lo\":%d,\"hi\":%d}",
                first ? "" : ",", coord, lo, hi);
            if (written < 0 || (size_t)written >= sizeof json - used) break;
            used += (size_t)written;
            first = false;
        }
    }

    snprintf(json + used, sizeof json - used, "]}");
    return new_string(env, json);
}

/**
 * Check whether a continuous search is currently in progress.
 */
JNIEXPORT jboolean JNICALL
Java_com_eklos_astraia_EdaxContinuousBridge_nativeIsSearchRunning(
    JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return (atomic_load(&g_active_search_ptr) != 0) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Force an immediate observer snapshot (useful when the UI needs the
 * latest data right now, e.g. after a configuration change).
 */
JNIEXPORT void JNICALL
Java_com_eklos_astraia_EdaxContinuousBridge_nativeRequestSnapshot(
    JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;

    Search *search = (Search *)atomic_load(&g_active_search_ptr);
    if (search != NULL && search->result != NULL) {
        cont_observer(search->result);
    }
}
