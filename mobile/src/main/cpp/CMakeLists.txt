cmake_minimum_required(VERSION 3.10.2)
project(crvusbfs C)

add_library(crvusbfs SHARED usbfs_jni.c)

target_compile_options(crvusbfs PRIVATE -Wall -Wextra -Werror)
