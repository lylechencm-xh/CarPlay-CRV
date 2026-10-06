LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := ../../../../../shared/src/main/jni/linux_i2c_jni.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
