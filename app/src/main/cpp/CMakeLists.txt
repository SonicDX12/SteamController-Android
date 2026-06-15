cmake_minimum_required(VERSION 3.22.1)
project(uinput_jni)

add_library(uinput_jni SHARED uinput_jni.cpp)

find_library(log-lib log)
target_link_libraries(uinput_jni ${log-lib})

target_compile_options(uinput_jni PRIVATE -Wall -Wextra -fvisibility=hidden)
