cmake_minimum_required(VERSION 3.22)
project(termux_bridge LANGUAGES C)

# Pointing directly to the C file that is inside the submodule
add_library(termux SHARED termux-source/terminal-emulator/src/main/jni/termux.c)

# We linked the native Android log library
target_link_libraries(termux log)
