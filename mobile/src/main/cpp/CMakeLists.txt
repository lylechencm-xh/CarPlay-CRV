cmake_minimum_required(VERSION 3.10.2)
project(crvusbfs C)

add_library(crvusbfs SHARED usbfs_jni.c)

target_compile_options(crvusbfs PRIVATE -Wall -Wextra -Werror)

# Optional onboard MFi authentication coprocessor access through a board-provided /dev/i2c-N node.
# The Java/Kotlin side only opens a node explicitly selected by the user; no blind bus scan occurs.
add_library(xcertplay_i2c SHARED ../../../../shared/src/main/jni/linux_i2c_jni.c)
target_compile_options(xcertplay_i2c PRIVATE -Wall -Wextra -Werror)
