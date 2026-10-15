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

# MesenCE is left untouched: each file a patch in external/patches/ changes is copied into the
# build tree, patched there and built in place of the original.
set(PATCHES_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/patches)
set(PATCHED_DIR ${CMAKE_CURRENT_BINARY_DIR}/mesen-patched)
file(REMOVE_RECURSE ${PATCHED_DIR})
file(GLOB MESEN_PATCHES ${PATCHES_DIR}/*.patch)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${MESEN_PATCHES})
foreach(PATCH ${MESEN_PATCHES})
    file(STRINGS ${PATCH} PATCHED_FILES REGEX "^\\+\\+\\+ b/")
    foreach(LINE ${PATCHED_FILES})
        string(REGEX REPLACE "^\\+\\+\\+ b/([^\t]*).*" "\\1" FILE ${LINE})
        if(NOT EXISTS ${PATCHED_DIR}/${FILE})
            configure_file(${MESEN_DIR}/${FILE} ${PATCHED_DIR}/${FILE} COPYONLY)
            list(REMOVE_ITEM MESEN_CORE_SOURCES ${MESEN_DIR}/${FILE})
            if(FILE MATCHES "\\.cpp$")
                list(APPEND MESEN_CORE_SOURCES ${PATCHED_DIR}/${FILE})
            endif()
        endif()
    endforeach()
    execute_process(COMMAND patch -p1 -s -i ${PATCH} WORKING_DIRECTORY ${PATCHED_DIR} RESULT_VARIABLE PATCH_RESULT)
    if(NOT PATCH_RESULT EQUAL 0)
        message(FATAL_ERROR "${PATCH} does not apply to MesenCE")
    endif()
endforeach()

add_library(mesen STATIC
    ${MESEN_CORE_SOURCES} ${MESEN_UTIL_SOURCES} ${MESEN_7Z_SOURCES} ${MESEN_LUA_SOURCES})
# The patched copies come first, so a patched header is found before the original.
target_include_directories(mesen PUBLIC ${PATCHED_DIR}/Core ${MESEN_DIR} ${MESEN_DIR}/Core ${MESEN_DIR}/Utilities)
# The emulation is slow unoptimized, so the core is built -O3 in every build type.
# Each function and object in its own section, so the link can drop what nothing calls.
target_compile_options(mesen PRIVATE -O3 -fPIC -w -ffunction-sections -fdata-sections)
# Only SNES games load (external/patches/snes-only.patch), and the debugger is never started.
target_compile_definitions(mesen PRIVATE LUA_USE_POSIX MESEN_SNES_ONLY)

add_library(emucore SHARED
    jni.cpp
    android_host.cpp)
target_compile_options(emucore PRIVATE -O2 -Wall -ffunction-sections -fdata-sections)
# Only the JNI functions are exported: MesenCE's symbols would otherwise all be kept as the
# library's interface. ThinLTO made the library larger (7.1 MB against 7.0 MB), so it is off.
target_link_options(emucore PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(emucore PRIVATE mesen android log aaudio EGL GLESv2)
