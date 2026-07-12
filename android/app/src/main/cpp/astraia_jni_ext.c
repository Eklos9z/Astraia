#include <jni.h>
#include "board.h"
#include "book.h"
#include "const.h"
#include "eval.h"
#include "move.h"
#include "options.h"
#include "search.h"
#include "util.h"
#include <inttypes.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>

/* Forward declarations from astraia_jni.c */
extern pthread_mutex_t engine_mutex;
extern bool eval_ready;
static bool book_ready = 0;
static Book edax_book;
extern jstring new_string(JNIEnv*, const char*);
extern void square_to_coord(int, char*);
extern bool parse_board_text(const char*, Board*, int*, char*, size_t);
extern void build_pv_string(const Line*, char*, size_t);
extern bool file_exists(const char*);

/* Helper: count discs of a given color */
static int disc_count_ext(const char *board, char disc) {
    int total = 0;
    for (int i = 0; i < 64; ++i) if (board[i] == disc) ++total;
    return total;
}

/* Helper: JSON-escape a string */
static void json_escape_ext(const char *src, char *dst, size_t sz) {
    size_t j = 0;
    for (size_t i = 0; src[i] && j + 2 < sz; ++i) {
        switch (src[i]) {
        case 0x22: dst[j++] = 0x5c; dst[j++] = 0x22; break;
        case 0x5c: dst[j++] = 0x5c; dst[j++] = 0x5c; break;
        case 0x0a: dst[j++] = 0x5c; dst[j++] = 0x6e; break;
        case 0x0d: dst[j++] = 0x5c; dst[j++] = 0x72; break;
        default:   dst[j++] = src[i]; break;
        }
    }
    dst[j] = 0;
}


/* Stub book functions -- full book.c has missing dependencies for Android */
void book_init(Book *book) { (void)book; }
void book_free(Book *book) { (void)book; }
void book_load(Book *book, const char *path) { (void)book; (void)path; }
bool book_get_moves(Book *book, const Board *board, MoveList *movelist) {
    (void)book; (void)board; (void)movelist;
    return false;
}
JNIEXPORT jstring JNICALL Java_com_eklos_astraia_EdaxEngine_nativeHint(
    JNIEnv *env, jclass clazz, jstring board_text, jint level, jint count)
{
    (void)clazz;
    const char *text = (*env)->GetStringUTFChars(env, board_text, NULL);
    Board board; int player; char error[128]; char json[8192];
    pthread_mutex_lock(&engine_mutex);
    if (!eval_ready) {
        snprintf(json, sizeof json, "{\"ok\":false,\"error\":\"eval.dat is missing\"}");
    } else if (!parse_board_text(text, &board, &player, error, sizeof error)) {
        snprintf(json, sizeof json, "{\"ok\":false,\"error\":\"%s\"}", error);
    } else {
        if (level < 0) level = 0; if (level > 60) level = 60;
        if (count < 1) count = 1; if (count > 10) count = 10;
        uint64_t moves = board_get_moves(&board);
        int move_indices[60], n_moves = 0;
        for (int x = A1; x <= H8 && n_moves < 60; ++x)
            if (moves & x_to_bit(x)) move_indices[n_moves++] = x;
        char moves_json[6144]; size_t mj_used = 0; moves_json[0] = 0;
        for (int i = 0; i < n_moves && i < count; ++i) {
            Search s; search_init(&s); search_set_board(&s, &board, player);
            search_set_level(&s, level, s.n_empties);

            /* Replace full movelist with a single-move movelist so the
             * search evaluates ONLY this move and returns its individual
             * score — not the global best-move score.
             *
             * The movelist uses move[0] as a sentinel; actual moves start
             * at move[1].  We unlink the original list and insert a single
             * move node. */
            s.movelist.n_moves = 1;
            s.movelist.move[0].next = &s.movelist.move[1];
            s.movelist.move[1].x = move_indices[i];
            board_get_move(&board, move_indices[i], &s.movelist.move[1]);
            s.movelist.move[1].next = NULL;

            search_run(&s);
            char move_str[5], pv_str[256];
            square_to_coord(s.result->move, move_str);
            build_pv_string(&s.result->pv, pv_str, sizeof pv_str);
            char escaped_pv[512]; json_escape_ext(pv_str, escaped_pv, sizeof escaped_pv);
            int written = snprintf(moves_json + mj_used, sizeof moves_json - mj_used,
                "%s{\"move\":\"%s\",\"score\":%d,\"depth\":%d,\"pv\":\"%s\"}",
                i ? "," : "", move_str, s.result->score, s.result->depth, escaped_pv);
            if (written < 0 || (size_t)written >= sizeof moves_json - mj_used) break;
            mj_used += (size_t)written;
            search_free(&s);
        }
        snprintf(json, sizeof json, "{\"ok\":true,\"moves\":[%s]}", moves_json);
    }
    pthread_mutex_unlock(&engine_mutex);
    (*env)->ReleaseStringUTFChars(env, board_text, text);
    return new_string(env, json);
}

JNIEXPORT jstring JNICALL Java_com_eklos_astraia_EdaxEngine_nativeGameInfo(
    JNIEnv *env, jclass clazz, jstring board_text)
{
    (void)clazz;
    const char *text = (*env)->GetStringUTFChars(env, board_text, NULL);
    char json[256];
    pthread_mutex_lock(&engine_mutex);
    int black = disc_count_ext(text, 'X'), white = disc_count_ext(text, 'O');
    int empties = 64 - black - white;
    bool over = (empties == 0 || black == 0 || white == 0);
    const char *winner = "none";
    if (over) winner = (black > white) ? "black" : (white > black) ? "white" : "draw";
    snprintf(json, sizeof json,
        "{\"blackDiscs\":%d,\"whiteDiscs\":%d,\"empties\":%d,\"gameOver\":%s,\"winner\":\"%s\"}",
        black, white, empties, over ? "true" : "false", winner);
    pthread_mutex_unlock(&engine_mutex);
    (*env)->ReleaseStringUTFChars(env, board_text, text);
    return new_string(env, json);
}

JNIEXPORT jstring JNICALL Java_com_eklos_astraia_EdaxEngine_nativeUndo(
    JNIEnv *env, jclass clazz, jstring board_text, jstring move_text)
{
    (void)clazz;
    const char *text = (*env)->GetStringUTFChars(env, board_text, NULL);
    const char *move_str = (*env)->GetStringUTFChars(env, move_text, NULL);
    Board board; int player; char error[128]; char out[128];
    pthread_mutex_lock(&engine_mutex);
    if (!parse_board_text(text, &board, &player, error, sizeof error)) {
        snprintf(out, sizeof out, "ERROR: %s", error);
    } else {
        int x = (int)(move_str[0] - 'a') + (int)(move_str[1] - '1') * 8;
        if (x < 0 || x > 63) { snprintf(out, sizeof out, "ERROR: bad move"); }
        else {
            Move move; board_get_move(&board, x, &move);
            board_restore(&board, &move);
            board_to_string(&board, player ^ 1, out);
        }
    }
    pthread_mutex_unlock(&engine_mutex);
    (*env)->ReleaseStringUTFChars(env, board_text, text);
    (*env)->ReleaseStringUTFChars(env, move_text, move_str);
    return new_string(env, out);
}

JNIEXPORT void JNICALL Java_com_eklos_astraia_EdaxEngine_nativeSetPlayType(
    JNIEnv *env, jclass clazz, jint play_type, jlong time_sec)
{
    (void)env; (void)clazz;
    pthread_mutex_lock(&engine_mutex);
    if (play_type == 0) options.play_type = EDAX_FIXED_LEVEL;
    else if (play_type == 1) options.play_type = EDAX_TIME_PER_GAME;
    else options.play_type = EDAX_TIME_PER_MOVE;
    if (play_type > 0) options.time = time_sec * 1000;
    options_bound();
    pthread_mutex_unlock(&engine_mutex);
}

JNIEXPORT void JNICALL Java_com_eklos_astraia_EdaxEngine_nativeSetPondering(
    JNIEnv *env, jclass clazz, jboolean enable)
{
    (void)env; (void)clazz;
    options.can_ponder = enable;
}

JNIEXPORT jstring JNICALL Java_com_eklos_astraia_EdaxEngine_nativeBookLoad(
    JNIEnv *env, jclass clazz, jstring book_path)
{
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, book_path, NULL);
    char result[128];
    pthread_mutex_lock(&engine_mutex);
    if (path && path[0] && file_exists(path)) {
        if (book_ready) book_free(&edax_book);
        book_init(&edax_book); book_load(&edax_book, path);
        book_ready = 1; options.book_allowed = 1;
        free(options.book_file); options.book_file = string_duplicate(path);
        snprintf(result, sizeof result, "OK");
    } else snprintf(result, sizeof result, "ERROR: book file not found");
    pthread_mutex_unlock(&engine_mutex);
    if (path) (*env)->ReleaseStringUTFChars(env, book_path, path);
    return new_string(env, result);
}

JNIEXPORT jstring JNICALL Java_com_eklos_astraia_EdaxEngine_nativeBookMove(
    JNIEnv *env, jclass clazz, jstring board_text)
{
    (void)clazz;
    const char *text = (*env)->GetStringUTFChars(env, board_text, NULL);
    Board board; int player; char error[128]; char out[16] = "";
    pthread_mutex_lock(&engine_mutex);
    if (book_ready && parse_board_text(text, &board, &player, error, sizeof error)) {
        if (options.book_allowed) {
            MoveList movelist;
            if (book_get_moves(&edax_book, &board, &movelist))
                square_to_coord(movelist.move[0].x, out);
        }
    }
    pthread_mutex_unlock(&engine_mutex);
    (*env)->ReleaseStringUTFChars(env, board_text, text);
    return new_string(env, out);
}

JNIEXPORT void JNICALL Java_com_eklos_astraia_EdaxEngine_nativeSetBookEnabled(
    JNIEnv *env, jclass clazz, jboolean enable)
{
    (void)env; (void)clazz;
    pthread_mutex_lock(&engine_mutex);
    options.book_allowed = (book_ready && enable);
    pthread_mutex_unlock(&engine_mutex);
}
