#include <jni.h>

#include "bit.h"
#include "board.h"
#include "const.h"
#include "eval.h"
#include "move.h"
#include "options.h"
#include "search.h"
#include "stats.h"
#include "util.h"

#include <inttypes.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

pthread_mutex_t engine_mutex = PTHREAD_MUTEX_INITIALIZER;
static bool core_ready = false;
bool eval_ready = false;

jstring new_string(JNIEnv *env, const char *text)
{
	return (*env)->NewStringUTF(env, text ? text : "");
}

void square_to_coord(int x, char out[5])
{
	if (x == PASS) {
		strcpy(out, "pass");
	} else if (x == NOMOVE) {
		strcpy(out, "--");
	} else if (x >= A1 && x <= H8) {
		out[0] = (char)('a' + (x & 7));
		out[1] = (char)('1' + (x >> 3));
		out[2] = '\0';
	} else {
		strcpy(out, "??");
	}
}

int coord_to_square(const char *move)
{
	if (move == NULL) return NOMOVE;
	if (strcmp(move, "pass") == 0 || strcmp(move, "pa") == 0) return PASS;
	if (strlen(move) < 2) return NOMOVE;

	char file = move[0];
	char rank = move[1];
	if ('A' <= file && file <= 'H') file = (char)(file - 'A' + 'a');
	if (file < 'a' || file > 'h' || rank < '1' || rank > '8') return NOMOVE;
	return (rank - '1') * 8 + (file - 'a');
}

bool file_exists(const char *path)
{
	FILE *file = fopen(path, "rb");
	if (file == NULL) return false;
	fclose(file);
	return true;
}

static void configure_core(int hash_bits, int threads)
{
	if (hash_bits < 10) hash_bits = 10;
	if (hash_bits > 26) hash_bits = 26;
	if (threads < 1) threads = 1;
	if (threads >= MAX_THREADS) threads = MAX_THREADS - 1;

	options.hash_table_size = hash_bits;
	options.n_task = threads;
	options.verbosity = 0;
	options.noise = 61;
	options.info = false;
	options.can_ponder = false;
	options.book_allowed = false;
	options.play_type = EDAX_FIXED_LEVEL;
	options_bound();

	if (!core_ready) {
		edge_stability_init();
		statistics_init();
		search_global_init();
		core_ready = true;
	}
}

bool parse_board_text(const char *text, Board *board, int *player, char *error, size_t error_size)
{
	if (text == NULL) {
		snprintf(error, error_size, "Board is null");
		return false;
	}
	if (strlen(text) < 65) {
		snprintf(error, error_size, "Board must contain 64 squares and a side to move");
		return false;
	}
	for (int i = 0; i < 64; ++i) {
		const char c = text[i];
		if (c != 'X' && c != 'x' && c != '*' && c != 'B' && c != 'b' &&
			c != 'O' && c != 'o' && c != 'W' && c != 'w' &&
			c != '-' && c != '.') {
			snprintf(error, error_size, "Bad square at index %d", i);
			return false;
		}
	}

	const int side = board_set(board, text);
	if (side != BLACK && side != WHITE) {
		snprintf(error, error_size, "Missing side to move");
		return false;
	}

	*player = side;
	return true;
}

void build_pv_string(const Line *pv, char *out, size_t out_size)
{
	size_t used = 0;
	out[0] = '\0';
	for (int i = 0; i < pv->n_moves; ++i) {
		char coord[5];
		square_to_coord(pv->move[i], coord);
		const int written = snprintf(out + used, out_size - used, "%s%s", i ? " " : "", coord);
		if (written < 0 || (size_t)written >= out_size - used) break;
		used += (size_t)written;
	}
}

JNIEXPORT jstring JNICALL Java_com_eklos_astraia_EdaxEngine_nativeInitialize(
	JNIEnv *env, jclass clazz, jstring eval_path, jint hash_bits, jint threads)
{
	(void)clazz;

	const char *path = NULL;
	if (eval_path != NULL) {
		path = (*env)->GetStringUTFChars(env, eval_path, NULL);
	}

	pthread_mutex_lock(&engine_mutex);
	configure_core(hash_bits, threads);

	char message[512];
	if (path != NULL && path[0] != '\0' && file_exists(path)) {
		if (!eval_ready) {
			free(options.eval_file);
			options.eval_file = string_duplicate(path);
			eval_open(options.eval_file);
			eval_ready = true;
		}
		snprintf(message, sizeof message, "OK");
	} else if (eval_ready) {
		snprintf(message, sizeof message, "OK");
	} else {
		snprintf(message, sizeof message, "MISSING_EVAL");
	}
	pthread_mutex_unlock(&engine_mutex);

	if (path != NULL) {
		(*env)->ReleaseStringUTFChars(env, eval_path, path);
	}
	return new_string(env, message);
}

JNIEXPORT jstring JNICALL Java_com_eklos_astraia_EdaxEngine_nativeInitialBoard(JNIEnv *env, jclass clazz)
{
	(void)clazz;
	Board board;
	char out[67];
	board_init(&board);
	board_to_string(&board, BLACK, out);
	return new_string(env, out);
}

JNIEXPORT jstring JNICALL Java_com_eklos_astraia_EdaxEngine_nativeLegalMoves(
	JNIEnv *env, jclass clazz, jstring board_text)
{
	(void)clazz;
	const char *text = (*env)->GetStringUTFChars(env, board_text, NULL);
	Board board;
	int player;
	char error[128];
	char out[192] = "";

	pthread_mutex_lock(&engine_mutex);
	if (parse_board_text(text, &board, &player, error, sizeof error)) {
		(void)player;
		const uint64_t moves = board_get_moves(&board);
		if (moves == 0) {
			if (can_move(board.opponent, board.player)) {
				strcpy(out, "pass");
			}
		} else {
			size_t used = 0;
			for (int x = A1; x <= H8; ++x) {
				if ((moves & x_to_bit(x)) != 0) {
					char coord[5];
					square_to_coord(x, coord);
					const int written = snprintf(out + used, sizeof out - used, "%s%s", used ? "," : "", coord);
					if (written < 0 || (size_t)written >= sizeof out - used) break;
					used += (size_t)written;
				}
			}
		}
	}
	pthread_mutex_unlock(&engine_mutex);

	(*env)->ReleaseStringUTFChars(env, board_text, text);
	return new_string(env, out);
}

JNIEXPORT jstring JNICALL Java_com_eklos_astraia_EdaxEngine_nativePlayMove(
	JNIEnv *env, jclass clazz, jstring board_text, jstring move_text)
{
	(void)clazz;
	const char *text = (*env)->GetStringUTFChars(env, board_text, NULL);
	const char *move_string = (*env)->GetStringUTFChars(env, move_text, NULL);
	Board board;
	Move move;
	int player;
	char error[128];
	char out[128];

	pthread_mutex_lock(&engine_mutex);
	if (!parse_board_text(text, &board, &player, error, sizeof error)) {
		snprintf(out, sizeof out, "ERROR: %s", error);
	} else {
		const int x = coord_to_square(move_string);
		if (x == PASS) {
			if (can_move(board.player, board.opponent)) {
				snprintf(out, sizeof out, "ERROR: pass is not legal");
			} else if (!can_move(board.opponent, board.player)) {
				snprintf(out, sizeof out, "ERROR: game is over");
			} else {
				board_pass(&board);
				board_to_string(&board, player ^ 1, out);
			}
		} else if (x < A1 || x > H8) {
			snprintf(out, sizeof out, "ERROR: bad move");
		} else {
			board_get_move(&board, x, &move);
			if (!board_check_move(&board, &move)) {
				snprintf(out, sizeof out, "ERROR: illegal move");
			} else {
				board_update(&board, &move);
				board_to_string(&board, player ^ 1, out);
			}
		}
	}
	pthread_mutex_unlock(&engine_mutex);

	(*env)->ReleaseStringUTFChars(env, board_text, text);
	(*env)->ReleaseStringUTFChars(env, move_text, move_string);
	return new_string(env, out);
}

JNIEXPORT jboolean JNICALL Java_com_eklos_astraia_EdaxEngine_nativeIsGameOver(
	JNIEnv *env, jclass clazz, jstring board_text)
{
	(void)clazz;
	const char *text = (*env)->GetStringUTFChars(env, board_text, NULL);
	Board board;
	int player;
	char error[128];
	bool game_over = false;

	pthread_mutex_lock(&engine_mutex);
	if (parse_board_text(text, &board, &player, error, sizeof error)) {
		(void)player;
		game_over = board_is_game_over(&board);
	}
	pthread_mutex_unlock(&engine_mutex);

	(*env)->ReleaseStringUTFChars(env, board_text, text);
	return game_over ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_eklos_astraia_EdaxEngine_nativeAnalyze(
	JNIEnv *env, jclass clazz, jstring board_text, jint level, jint move_time_ms)
{
	(void)clazz;
	const char *text = (*env)->GetStringUTFChars(env, board_text, NULL);
	Board board;
	int player;
	char error[128];
	char json[2048];

	pthread_mutex_lock(&engine_mutex);
	if (!eval_ready) {
		snprintf(json, sizeof json, "{\"ok\":false,\"error\":\"eval.dat is missing\"}");
	} else if (!parse_board_text(text, &board, &player, error, sizeof error)) {
		snprintf(json, sizeof json, "{\"ok\":false,\"error\":\"%s\"}", error);
	} else {
		if (level < 0) level = 0;
		if (level > 60) level = 60;

		Search search;
		search_init(&search);
		search.options.verbosity = 0;
		search_set_board(&search, &board, player);
		search_set_level(&search, level, search.n_empties);
		if (move_time_ms > 0) {
			search_set_move_time(&search, move_time_ms);
		}
		search_run(&search);

		char move[5];
		char pv[256];
		Result result = *search.result;
		square_to_coord(result.move, move);
		build_pv_string(&result.pv, pv, sizeof pv);

		int black = 0, white = 0;
		for (int i = 0; i < 64; ++i) {
			if (text[i] == 'X') ++black;
			else if (text[i] == 'O') ++white;
		}

		snprintf(
			json, sizeof json,
			"{\"ok\":true,\"move\":\"%s\",\"score\":%d,\"depth\":%d,\"timeMs\":%" PRId64 ",\"nodes\":%" PRIu64 ",\"pv\":\"%s\",\"blackDiscs\":%d,\"whiteDiscs\":%d}",
			move, result.score, result.depth, result.time, result.n_nodes, pv,
			black, white);

		search_free(&search);
	}
	pthread_mutex_unlock(&engine_mutex);

	(*env)->ReleaseStringUTFChars(env, board_text, text);
	return new_string(env, json);
}

JNIEXPORT void JNICALL Java_com_eklos_astraia_EdaxEngine_nativeShutdown(JNIEnv *env, jclass clazz)
{
	(void)env;
	(void)clazz;
}