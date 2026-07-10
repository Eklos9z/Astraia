cmake_minimum_required(VERSION 3.22.1)

project(astraia_android C)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(
    astraia_jni
    SHARED
    astraia_jni.c
    astraia_jni_ext.c
    astraia_jni_cont.c
    ../../../../../src/android-edax.c
)

target_include_directories(
    astraia_jni
    PRIVATE
    ../../../../../src
)

target_compile_definitions(
    astraia_jni
    PRIVATE
    ANDROID=1
    __STDC_NO_THREADS__=1
    USE_SIMD=0
    USE_CRC32C=0
    MOVE_GENERATOR=MOVE_GENERATOR_KINDERGARTEN
    COUNT_LAST_FLIP=COUNT_LAST_FLIP_PLAIN
)

target_compile_options(
    astraia_jni
    PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-missing-field-initializers
)

find_library(log-lib log)

target_link_libraries(
    astraia_jni
    PRIVATE
    ${log-lib}
    m
)
