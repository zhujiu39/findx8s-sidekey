#define _GNU_SOURCE
#include "torch.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static pid_t worker_pid;
static uint64_t restart_at;

static uint64_t clock_ms(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) return 0;
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

void torch_stop(void)
{
    if (worker_pid > 0) (void)kill(worker_pid, SIGKILL);
}

/**
 * @功能：按需管理系统手电筒服务，与监听进程绑定生存期。
 * @日期：2026-09-16
 * @参数：wanted 为配置是否需要手电筒；module/data 为绝对目录；now 为单调毫秒。
 * @返回值：当前子进程 PID，尚未启动时为 0。
 * @使用说明：退出父进程时内核终止子进程，使 CameraService 释放本客户端持有的灯光。
 */
pid_t torch_supervise(bool wanted, const char *module, const char *data, uint64_t now)
{
    char output[1024];
    snprintf(output, sizeof(output), "%s/torch-service.log", data);
    struct stat st;
    if (stat(output, &st) == 0 && st.st_size > 65536) (void)truncate(output, 0);
    if (worker_pid > 0) {
        pid_t result = waitpid(worker_pid, NULL, WNOHANG);
        if (result == worker_pid || (result < 0 && errno == ECHILD)) worker_pid = 0;
    }
    if (!wanted) { torch_stop(); restart_at = 0; return 0; }
    if (worker_pid > 0 || now < restart_at) return worker_pid;
    restart_at = now + 10000;
    char jar[1024], socket_name[80];
    snprintf(jar, sizeof(jar), "%s/lib/torch.jar", module);
    snprintf(socket_name, sizeof(socket_name), "oppo_sidekey.torch.%ld", (long)getpid());
    if (access(jar, R_OK) != 0) return 0;
    pid_t parent = getpid(), child = fork();
    if (child < 0) return 0;
    if (child == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(1);
        int fd = open(output, O_WRONLY | O_CREAT | O_TRUNC | O_APPEND, 0600);
        if (fd < 0) _exit(1);
        if (dup2(fd, STDOUT_FILENO) < 0 || dup2(fd, STDERR_FILENO) < 0) _exit(1);
        if (fd > 2) close(fd);
        if (setenv("CLASSPATH", jar, 1) != 0) _exit(1);
        execl("/system/bin/app_process", "app_process", "/system/bin", "--nice-name=oppo-sidekey-torch",
              "cn.sidekey.TorchService", data, socket_name, (char *)NULL);
        perror("启动手电筒服务失败"); _exit(127);
    }
    worker_pid = child;
    return worker_pid;
}

int torch_toggle(const char *data, char *message, size_t cap)
{
    if (!data || !message || cap < 2) return 2;
    snprintf(message, cap, "手电筒服务未就绪，请查看手电筒日志");
    char path[1024];
    snprintf(path, sizeof(path), "%s/daemon.lock", data);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    uint64_t until = clock_ms() + 8000;
    int fd = -1;
    /* 只在发送请求前等待就绪；发送后的失败不能重试，否则会把灯再切换一次。 */
    while (clock_ms() < until) {
        FILE *file = fopen(path, "r");
        long owner = 0;
        int n = 0;
        if (file) { n = fscanf(file, "%ld", &owner); fclose(file); }
        if (n != 1 || owner <= 1) { usleep(100000); continue; }
        int length = snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1, "oppo_sidekey.torch.%ld", owner);
        socklen_t address_size = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + length);
        fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        if (fd < 0) return 1;
        if (connect(fd, (struct sockaddr *)&address, address_size) == 0) break;
        int saved_errno = errno;
        close(fd); fd = -1;
        if (saved_errno != ENOENT && saved_errno != ECONNREFUSED && saved_errno != EAGAIN) {
            snprintf(message, cap, "手电筒连接失败：%s", strerror(saved_errno)); return 1;
        }
        usleep(100000);
    }
    if (fd < 0) return 1;
    struct ucred peer;
    socklen_t peer_size = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) != 0 || peer.uid != 0) {
        close(fd); snprintf(message, cap, "手电筒服务身份校验失败"); return 1;
    }
    if (send(fd, "T", 1, MSG_NOSIGNAL) != 1) { close(fd); return 1; }
    size_t used = 0;
    while (clock_ms() < until && used < cap - 1) {
        struct pollfd event = {.fd = fd, .events = POLLIN};
        int result = poll(&event, 1, 200);
        if (result < 0 && errno == EINTR) continue;
        if (result < 0) break;
        if (!result) continue;
        ssize_t count = recv(fd, message + used, cap - 1 - used, 0);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count <= 0) break;
        used += (size_t)count; message[used] = 0;
        char *newline = strchr(message, '\n');
        if (newline) { *newline = 0; close(fd); return strncmp(message, "OK ", 3) ? 1 : 0; }
    }
    close(fd);
    snprintf(message, cap, "未收到切换结果，请检查实际灯光状态及手电筒日志");
    return 1;
}
