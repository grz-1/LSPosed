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

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <fcntl.h>

#include "logging.h"

#if defined(__LP64__)
# define LP_SELECT(lp32, lp64) lp64
#else
# define LP_SELECT(lp32, lp64) lp32
#endif

#define ID_VEC(is64, is_debug) (((is64) << 1) | (is_debug))

const char kSockName[] = "5291374ceda0aef7c5d86cd2a4f6a3ac";
static const int kIs64Bit = LP_SELECT(0, 1);

static int is_debug_version(const char *arg0) {
    const char *p = arg0;
    while (*p) p++;
    return (p > arg0 && *(p - 1) == 'd') ? 1 : 0;
}

static ssize_t xrecvmsg(int sockfd, struct msghdr *msg, int flags) {
    ssize_t rec;
    do {
        rec = recvmsg(sockfd, msg, flags);
    } while (rec < 0 && errno == EINTR);
    return rec;
}

static void *recv_fds(int sockfd, char *cmsgbuf, size_t bufsz, int cnt) {
    struct iovec iov = { .iov_base = &cnt, .iov_len = sizeof(cnt) };
    struct msghdr msg = {
        .msg_iov = &iov,
        .msg_iovlen = 1,
        .msg_control = cmsgbuf,
        .msg_controllen = bufsz
    };

    if (xrecvmsg(sockfd, &msg, MSG_WAITALL) != sizeof(cnt)) {
        return NULL;
    }

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) {
        return NULL;
    }
    if (msg.msg_controllen < CMSG_SPACE(sizeof(int) * cnt) || 
        cmsg->cmsg_len < CMSG_LEN(sizeof(int) * cnt)) {
        return NULL;
    }

    return CMSG_DATA(cmsg);
}

static int recv_fd(int sockfd) {
    char cmsgbuf[CMSG_SPACE(sizeof(int))];
    int result = -1;
    void *data = recv_fds(sockfd, cmsgbuf, sizeof(cmsgbuf), 1);
    if (data) {
        memcpy(&result, data, sizeof(int));
    }
    return result;
}

static int read_int(int fd) {
    int val;
    ssize_t bytes_read;
    do {
        bytes_read = read(fd, &val, sizeof(val));
    } while (bytes_read < 0 && errno == EINTR);
    return (bytes_read == sizeof(val)) ? val : -1;
}

static int write_int(int fd, int val) {
    if (fd < 0) return -1;
    ssize_t bytes_written;
    do {
        bytes_written = write(fd, &val, sizeof(val));
    } while (bytes_written < 0 && errno == EINTR);
    return (bytes_written == sizeof(val)) ? 0 : -1;
}

static int set_cloexec(int fd) {
    int flags = fcntl(fd, F_GETFD);
    if (flags == -1) return -1;
    if (fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == -1) return -1;
    return 0;
}

static int connect_to_server(const char* sock_name, int* sock_fd) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    struct sockaddr_un sock = { .sun_family = AF_UNIX };
    size_t i;
    for (i = 0; i < sizeof(sock.sun_path) - 2 && sock_name[i] != '\0'; i++) {
        sock.sun_path[1 + i] = sock_name[i];
    }
    sock.sun_path[1 + i] = '\0';
    
    size_t len = sizeof(sa_family_t) + i + 1;
    if (connect(fd, (struct sockaddr *) &sock, len)) {
        close(fd);
        return -1;
    }
    *sock_fd = fd;
    return 0;
}

int main(int argc, char **argv) {
    int sock_fd = -1;
    int stock_fd = -1;
    int ret = 1;
    char **new_argv = NULL;

    if (connect_to_server(kSockName, &sock_fd) < 0) goto cleanup;

    int id_vec = ID_VEC(kIs64Bit, is_debug_version(argv[0]));
    if (write_int(sock_fd, id_vec) < 0) goto cleanup;

    stock_fd = recv_fd(sock_fd);
    if (stock_fd < 0) goto cleanup;

    if (read_int(sock_fd) < 0) goto cleanup;
    close(sock_fd);
    sock_fd = -1;

    if (set_cloexec(stock_fd) < 0) goto cleanup;

    size_t new_argv_size = (argc + 2) * sizeof(char *);
    if (!(new_argv = malloc(new_argv_size))) goto cleanup;
    memcpy(new_argv, argv, argc * sizeof(char *));
    new_argv[argc] = "--inline-max-code-units=0";
    new_argv[argc + 1] = NULL;

    if (!getenv("LD_LIBRARY_PATH")) {
        static char libenv[] = "LD_LIBRARY_PATH=/apex/com.android.art/lib64:/apex/com.android.art/lib:/apex/com.android.os.statsd/lib64:/apex/com.android.os.statsd/lib";
        putenv(libenv);
    }

    fexecve(stock_fd, new_argv, environ);
    PLOGE("fexecve");
    ret = 2;

cleanup:
    if (sock_fd >= 0) close(sock_fd);
    if (stock_fd >= 0) close(stock_fd);
    free(new_argv);
    return ret;
}
