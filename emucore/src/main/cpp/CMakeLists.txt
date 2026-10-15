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
# Each function and object in its own section, so the link can drop what nothing calls.
target_compile_options(mesen PRIVATE -O3 -fPIC -w -ffunction-sections -fdata-sections)
target_compile_definitions(mesen PRIVATE LUA_USE_POSIX)

add_library(emucore SHARED
    jni.cpp
    android_host.cpp)
target_compile_options(emucore PRIVATE -O2 -Wall -ffunction-sections -fdata-sections)
# Only the JNI functions are exported: MesenCE's symbols would otherwise all be kept as the
# library's interface. ThinLTO made the library larger (7.1 MB against 7.0 MB), so it is off.
target_link_options(emucore PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(emucore PRIVATE mesen android log aaudio EGL GLESv2)
