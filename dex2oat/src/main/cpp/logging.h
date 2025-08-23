#ifndef LOGGING_H
#define LOGGING_H

#include <android/log.h>
#include <string.h>
#include <errno.h>

#ifndef LOG_TAG
#define LOG_TAG "LSPosedDex2Oat"
#endif

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define PLOGE(fmt) LOGE(fmt " failed with %d: %s", errno, strerror(errno))

#endif // LOGGING_H
