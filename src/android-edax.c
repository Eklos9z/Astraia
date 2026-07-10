/**
 * @file android-edax.c
 *
 * @brief Android/JNI engine aggregation unit.
 *
 * This keeps the native Android library separate from the command-line Edax
 * executable: no main(), no terminal UI loop, no network protocols.
 */

void usage(void) {}
void version(void) {}

#if defined(__ANDROID__) || defined(ANDROID)
	#include <stdlib.h>

	static void* edax_android_aligned_alloc(size_t alignment, size_t size)
	{
		void *ptr = NULL;
		return posix_memalign(&ptr, alignment, size) == 0 ? ptr : NULL;
	}

	#define aligned_alloc edax_android_aligned_alloc
#endif

/* miscellaneous utilities */
#include "options.c"
#include "util.c"

/* Stub logging globals -- not needed on Android, but the engine references them. */
Log engine_log = {NULL};
Log xboard_log = {NULL};

#include "stats.c"
#include "bit.c"
#include "crc32c.c"

/* move generation */
#include "flip.c"
#include "board.c"
#include "move.c"

/* eval & search */
#include "eval.c"
#include "hash.c"
#include "ybwc.c"
#include "search.c"
#include "endgame.c"
#include "midgame.c"
#include "root.c"
