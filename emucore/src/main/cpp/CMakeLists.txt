cmake_minimum_required(VERSION 3.22)
project(emucore C CXX)

# MesenCE has no CMake of its own (a VS solution and a makefile); this follows its makefile.
set(MESEN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/MesenCE)
get_filename_component(MESEN_DIR ${MESEN_DIR} ABSOLUTE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

file(GLOB_RECURSE MESEN_CORE_SOURCES CONFIGURE_DEPENDS ${MESEN_DIR}/Core/*.cpp)
file(GLOB_RECURSE MESEN_UTIL_SOURCES CONFIGURE_DEPENDS ${MESEN_DIR}/Utilities/*.cpp ${MESEN_DIR}/Utilities/*.c)
file(GLOB MESEN_7Z_SOURCES CONFIGURE_DEPENDS ${MESEN_DIR}/SevenZip/*.c)
file(GLOB MESEN_LUA_SOURCES CONFIGURE_DEPENDS ${MESEN_DIR}/Lua/*.c)

add_library(mesen STATIC
    ${MESEN_CORE_SOURCES} ${MESEN_UTIL_SOURCES} ${MESEN_7Z_SOURCES} ${MESEN_LUA_SOURCES})
target_include_directories(mesen PUBLIC ${MESEN_DIR} ${MESEN_DIR}/Core ${MESEN_DIR}/Utilities)
# The emulation is slow unoptimized, so the core is built -O3 in every build type.
target_compile_options(mesen PRIVATE -O3 -fPIC -w)
target_compile_definitions(mesen PRIVATE LUA_USE_POSIX)

add_library(emucore SHARED
    jni.cpp
    android_host.cpp)
target_compile_options(emucore PRIVATE -O2 -Wall)
target_link_libraries(emucore PRIVATE mesen android log aaudio EGL GLESv2)
