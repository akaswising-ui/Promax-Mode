cmake_minimum_required(VERSION 3.22.1)
project(promax_dsp LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

file(GLOB SHINE_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shine/*.c")
add_library(shine SHARED ${SHINE_SOURCES})
target_include_directories(shine PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/shine")

add_library(promax_dsp SHARED promax_dsp.cpp PromaxVoiceDsp.cpp)
find_library(log-lib log)
target_link_libraries(promax_dsp PRIVATE shine ${log-lib} m)
