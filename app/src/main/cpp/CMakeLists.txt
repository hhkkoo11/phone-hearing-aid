cmake_minimum_required(VERSION 3.22.1)

project(hearingaid_native C)

add_library(
        hearingaid_native
        SHARED
        ai_noise_suppressor.c
        rnnoise/celt_lpc.c
        rnnoise/denoise.c
        rnnoise/kiss_fft.c
        rnnoise/nnet.c
        rnnoise/nnet_default.c
        rnnoise/parse_lpcnet_weights.c
        rnnoise/pitch.c
        rnnoise/rnn.c
        rnnoise/rnnoise_data.c
        rnnoise/rnnoise_tables.c)

target_include_directories(
        hearingaid_native
        PRIVATE
        rnnoise)

target_compile_definitions(
        hearingaid_native
        PRIVATE
        RNNOISE_BUILD
        TRAINING=0)

target_compile_options(
        hearingaid_native
        PRIVATE
        -O3)

target_link_libraries(
        hearingaid_native
        log
        m)
