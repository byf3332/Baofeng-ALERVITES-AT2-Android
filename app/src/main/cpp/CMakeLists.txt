cmake_minimum_required(VERSION 3.22.1)
project(talkie_opencodec LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Vendored OpenCORE AMR tree used to build an app-local replacement for libtalkie.so.
set(OPENCORE_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/opencore-amr)
set(OC_BASE ${OPENCORE_ROOT}/opencore/codecs_v2/audio/gsm_amr)
set(AMR_BASE ${OC_BASE}/amr_nb)
set(ENC_SRC_DIR ${AMR_BASE}/enc/src)
set(DEC_SRC_DIR ${AMR_BASE}/dec/src)
set(COMMON_SRC_DIR ${AMR_BASE}/common/src)
set(OSCL_DIR ${OPENCORE_ROOT}/oscl)

file(GLOB ENC_SOURCES "${ENC_SRC_DIR}/*.cpp")
list(REMOVE_ITEM ENC_SOURCES "${ENC_SRC_DIR}/gsmamr_encoder_wrapper.cpp")

file(GLOB DEC_SOURCES "${DEC_SRC_DIR}/*.cpp")
list(REMOVE_ITEM DEC_SOURCES "${DEC_SRC_DIR}/decoder_gsm_amr.cpp" "${DEC_SRC_DIR}/pvgsmamrdecoder.cpp")

file(GLOB COMMON_SOURCES "${COMMON_SRC_DIR}/*.cpp")
list(REMOVE_ITEM COMMON_SOURCES
    "${COMMON_SRC_DIR}/bits2prm.cpp"
    "${COMMON_SRC_DIR}/copy.cpp"
    "${COMMON_SRC_DIR}/div_32.cpp"
    "${COMMON_SRC_DIR}/l_abs.cpp"
    "${COMMON_SRC_DIR}/r_fft.cpp"
    "${COMMON_SRC_DIR}/vad1.cpp"
    "${COMMON_SRC_DIR}/vad2.cpp"
)

set(OPENCORE_INCLUDE_DIRS
    ${OPENCORE_ROOT}/amrnb
    ${OSCL_DIR}
    ${ENC_SRC_DIR}
    ${DEC_SRC_DIR}
    ${AMR_BASE}/common/include
    ${AMR_BASE}/dec/include
    ${OC_BASE}/common/dec/include
)

add_library(opencore_amrnb STATIC
    ${OPENCORE_ROOT}/amrnb/wrapper.cpp
    ${ENC_SOURCES}
    ${DEC_SOURCES}
    ${COMMON_SOURCES}
)

target_include_directories(opencore_amrnb PRIVATE ${OPENCORE_INCLUDE_DIRS})
target_compile_options(opencore_amrnb PRIVATE -O3 -Wno-register)
set_target_properties(opencore_amrnb PROPERTIES POSITION_INDEPENDENT_CODE ON)

# Final drop-in shared library loaded by TalkieCodec.kt via System.loadLibrary("talkie").
add_library(talkie SHARED talkie_jni.cpp)
target_include_directories(talkie PRIVATE ${OPENCORE_INCLUDE_DIRS})
target_compile_options(talkie PRIVATE -O3)
target_link_libraries(talkie PRIVATE opencore_amrnb log)
