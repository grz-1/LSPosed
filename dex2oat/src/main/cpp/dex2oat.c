/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2022 LSPosed Contributors
 */

//
// Created by Nullptr on 2022/4/1.
//

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <dlfcn.h>
#include <map>
#include <string>
#include <string_view>
#include <vector>

#include <lsplt.hpp>

#include "logging.h"

#if defined(__LP64__)
# define LP_SELECT(lp32, lp64) lp64
#else
# define LP_SELECT(lp32, lp64) lp32
#endif

#define ID_VEC(is64, is_debug) (((is64) << 1) | (is_debug))

const char kSockName[] = "5291374ceda0aef7c5d86cd2a4f6a3ac\0";
const std::string_view parameter_to_remove = " --inline-max-code-units=0";

#define DCL_HOOK_FUNC(ret, func, ...)                                                              \
    ret (*old_##func)(__VA_ARGS__);                                                                \
    ret new_##func(__VA_ARGS__)

bool store_updated = false;

void UpdateKeyValueStore(std::map<std::string, std::string>* key_value, uint8_t* store) {
    LOGD("updating KeyValueStore");
    char* data_ptr = reinterpret_cast<char*>(store);
    if (key_value != nullptr) {
        auto it = key_value->begin();
        auto end = key_value->end();
        for (; it != end; ++it) {
            strlcpy(data_ptr, it->first.c_str(), it->first.length() + 1);
            data_ptr += it->first.length() + 1;
            strlcpy(data_ptr, it->second.c_str(), it->second.length() + 1);
            data_ptr += it->second.length() + 1;
        }
    }
    LOGD("KeyValueStore updated");
    store_updated = true;
}

DCL_HOOK_FUNC(uint32_t, _ZNK3art9OatHeader20GetKeyValueStoreSizeEv, void* header) {
    uint32_t size = old__ZNK3art9OatHeader20GetKeyValueStoreSizeEv(header);
    if (store_updated) {
        LOGD("OatHeader::GetKeyValueStoreSize() called on object at %p\n", header);
        size = size - parameter_to_remove.size();
    }
    return size;
}

DCL_HOOK_FUNC(uint8_t*, _ZNK3art9OatHeader16GetKeyValueStoreEv, void* header) {
    LOGD("OatHeader::GetKeyValueStore() called on object at %p\n", header);
    uint8_t* key_value_store_ = old__ZNK3art9OatHeader16GetKeyValueStoreEv(header);
    uint32_t key_value_store_size_ = old__ZNK3art9OatHeader20GetKeyValueStoreSizeEv(header);
    const char* ptr = reinterpret_cast<const char*>(key_value_store_);
    const char* end = ptr + key_value_store_size_;
    std::map<std::string, std::string> new_store = {};

    LOGD("scanning [%p-%p] for oat headers", ptr, end);
    while (ptr < end) {
        const char* str_end = reinterpret_cast<const char*>(memchr(ptr, 0, end - ptr));
        if (str_end == nullptr) [[unlikely]] {
            LOGE("failed to find str_end");
            return key_value_store_;
        }
        std::string_view key = std::string_view(ptr, str_end - ptr);
        const char* value_start = str_end + 1;
        const char* value_end =
            reinterpret_cast<const char*>(memchr(value_start, 0, end - value_start));
        if (value_end == nullptr) [[unlikely]] {
            LOGE("failed to find value_end");
            return key_value_store_;
        }
        std::string_view value = std::string_view(value_start, value_end - value_start);
        LOGV("header %s:%s", key.data(), value.data());
        if (key == "dex2oat-cmdline") {
            value = value.substr(0, value.size() - parameter_to_remove.size());
        }
        new_store.insert(std::make_pair(std::string(key), std::string(value)));
        ptr = value_end + 1;
    }
    UpdateKeyValueStore(&new_store, key_value_store_);

    return key_value_store_;
}

#undef DCL_HOOK_FUNC

void register_hook(dev_t dev, ino_t inode, const char* symbol, void* new_func, void** old_func) {
    LOGD("RegisterHook: %s, %p, %p", symbol, new_func, old_func);
    if (!lsplt::RegisterHook(dev, inode, symbol, new_func, old_func)) {
        LOGE("Failed to register plt_hook \"%s\"\n", symbol);
        return;
    }
}

#define PLT_HOOK_REGISTER_SYM(DEV, INODE, SYM, NAME)                                               \
    register_hook(DEV, INODE, SYM, reinterpret_cast<void*>(new_##NAME),                            \
                  reinterpret_cast<void**>(&old_##NAME))

#define PLT_HOOK_REGISTER(DEV, INODE, NAME) PLT_HOOK_REGISTER_SYM(DEV, INODE, #NAME, NAME)

static ssize_t xrecvmsg(int sockfd, struct msghdr *msg, int flags) {
    int rec = recvmsg(sockfd, msg, flags);
    if (rec < 0) {
        PLOGE("recvmsg");
    }
    return rec;
}

static void *recv_fds(int sockfd, char *cmsgbuf, size_t bufsz, int cnt) {
    struct iovec iov = {
            .iov_base = &cnt,
            .iov_len  = sizeof(cnt),
    };
    struct msghdr msg = {
            .msg_iov        = &iov,
            .msg_iovlen     = 1,
            .msg_control    = cmsgbuf,
            .msg_controllen = bufsz
    };

    xrecvmsg(sockfd, &msg, MSG_WAITALL);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);

    if (msg.msg_controllen != bufsz ||
        cmsg == NULL ||
        cmsg->cmsg_len != CMSG_LEN(sizeof(int) * cnt) ||
        cmsg->cmsg_level != SOL_SOCKET ||
        cmsg->cmsg_type != SCM_RIGHTS) {
        return NULL;
    }

    return CMSG_DATA(cmsg);
}

static int recv_fd(int sockfd) {
    char cmsgbuf[CMSG_SPACE(sizeof(int))];

    void *data = recv_fds(sockfd, cmsgbuf, sizeof(cmsgbuf), 1);
    if (data == NULL)
        return -1;

    int result;
    memcpy(&result, data, sizeof(int));
    return result;
}

static int read_int(int fd) {
    int val;
    if (read(fd, &val, sizeof(val)) != sizeof(val))
        return -1;
    return val;
}

static void write_int(int fd, int val) {
    if (fd < 0) return;
    write(fd, &val, sizeof(val));
}

int main(int argc, char **argv) {
    LOGD("dex2oat wrapper ppid=%d", getppid());
    
    bool should_hook = getenv("LSPOSED_DEX2OAT_HOOK") != nullptr;
    
    if (should_hook) {
        LOGD("Initializing OAT hook");
        dev_t dev = 0;
        ino_t inode = 0;
        for (auto& info : lsplt::MapInfo::Scan()) {
            if (info.path.starts_with("/apex/com.android.art/bin/dex2oat")) {
                dev = info.dev;
                inode = info.inode;
                break;
            }
        }
        LOGD("dex2oat binary %lu:%lu", dev, inode);

        PLT_HOOK_REGISTER(dev, inode, _ZNK3art9OatHeader20GetKeyValueStoreSizeEv);
        PLT_HOOK_REGISTER(dev, inode, _ZNK3art9OatHeader16GetKeyValueStoreEv);
        if (lsplt::CommitHook()) {
            LOGD("lsplt hooks done");
        };
    }
    
    struct sockaddr_un sock = {};
    sock.sun_family = AF_UNIX;
    strlcpy(sock.sun_path + 1, kSockName, sizeof(sock.sun_path) - 1);
    int sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    size_t len = sizeof(sa_family_t) + strlen(sock.sun_path + 1) + 1;
    if (connect(sock_fd, (struct sockaddr *) &sock, len)) {
        PLOGE("failed to connect to %s", sock.sun_path + 1);
        return 1;
    }
    write_int(sock_fd, ID_VEC(LP_SELECT(0, 1), strstr(argv[0], "dex2oatd") != NULL));
    int stock_fd = recv_fd(sock_fd);
    read_int(sock_fd);
    close(sock_fd);
    LOGD("sock: %s %d", sock.sun_path + 1, stock_fd);

    const char *new_argv[argc + 2];
    for (int i = 0; i < argc; i++) new_argv[i] = argv[i];
    new_argv[argc] = "--inline-max-code-units=0";
    new_argv[argc + 1] = NULL;

    if (getenv("LD_LIBRARY_PATH") == NULL) {
        char const *libenv =
                "LD_LIBRARY_PATH=/apex/com.android.art/lib64:/apex/com.android.art/lib"
                ":/apex/com.android.os.statsd/lib64:/apex/com.android.os.statsd/lib";
        putenv((char *)libenv);
    }

    setenv("LSPOSED_DEX2OAT_HOOK", "1", 1);
    
    fexecve(stock_fd, (char **) new_argv, environ);
    PLOGE("fexecve failed");
    return 2;
}