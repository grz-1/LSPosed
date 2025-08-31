#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "logging.h"

#define ID_VEC(is64, is_debug) (((is64) << 1) | (is_debug))

const char kSockName[] = "7d3f1a9c8e2b5f4a6c0d9e7b2a5f3c1d\0";

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
    struct sockaddr_un sock = {};
    sock.sun_family = AF_UNIX;
    strlcpy(sock.sun_path + 1, kSockName, sizeof(sock.sun_path) - 1);
    int sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    size_t len = sizeof(sa_family_t) + strlen(sock.sun_path + 1) + 1;
    if (connect(sock_fd, (struct sockaddr *) &sock, len)) {
        PLOGE("failed to connect to %s", sock.sun_path + 1);
        return 1;
    }
    int is64bit = sizeof(void*) == 8;
    int isDebug = strstr(argv[0], "dex2oatd") != NULL;
    write_int(sock_fd, ID_VEC(is64bit, isDebug));
    int stock_fd = recv_fd(sock_fd);
    read_int(sock_fd);
    close(sock_fd);
    LOGD("sock: %s %d", sock.sun_path + 1, stock_fd);

    char **new_argv = malloc((argc + 1) * sizeof(char *));
    for (int i = 0; i < argc; i++) new_argv[i] = argv[i];
    new_argv[argc] = NULL;

    const char* target_path = NULL;
    target_path = isDebug ? "/data/adb/modules/zygisk_lsposed/bin/dex2oat" : "/data/adb/modules/zygisk_lsposed/bin/dex2oat";
    new_argv[0] = (char*)target_path;

    if (getenv("LD_LIBRARY_PATH") == NULL) {
        setenv("LD_LIBRARY_PATH", "/apex/com.android.art/lib64:/apex/com.android.art/lib:/apex/com.android.os.statsd/lib64:/apex/com.android.os.statsd/lib", 1);
    }

    fexecve(stock_fd, new_argv, environ);
    PLOGE("fexecve failed");
    return 2;
}