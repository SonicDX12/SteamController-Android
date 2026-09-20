cmake_minimum_required(VERSION 3.22.1)
project(uinput_jni)

add_library(uinput_jni SHARED uinput_jni.cpp)

find_library(log-lib log)
target_link_libraries(uinput_jni ${log-lib})

target_compile_options(uinput_jni PRIVATE -Wall -Wextra -fvisibility=hidden)

# Align the ELF LOAD segments on 16 KB. Devices with 16 KB pages — Android 15 and
# up — refuse to load a library laid out for 4 KB ones, and the Play Store requires
# it. NDK r27 does this by default, but AGP 8.5 pulls r26, which does not, so ask
# for it explicitly: the flag is harmless on a toolchain that already complies.
target_link_options(uinput_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
