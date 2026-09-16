#define _GNU_SOURCE
#include "haptic.h"
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

/* 与动作队列的突发容量一致，不为反馈无界创建进程。 */
#define HAPTIC_SLOTS 4
#define HAPTIC_TIMEOUT_MS 2000
typedef struct { pid_t pid; uint64_t started; bool cancelled, timed_out; } HapticChild;
static HapticChild children[HAPTIC_SLOTS];

/**
 * @功能：发送一次系统短震动，主输入循环无需等待 Binder 或震动完成。
 * @日期：2026-09-16
 * @参数：[输入] now - 单调毫秒时间。
 * @返回值：0 已发起；1 创建失败；2 反馈请求队列已满。
 * @使用说明：仅在有效手势入队时调用一次；父进程退出时内核终止反馈客户端。
 */
int haptic_trigger(uint64_t now)
{
    HapticChild *slot = NULL;
    for (int i = 0; i < HAPTIC_SLOTS; i++)
        if (children[i].pid == 0) { slot = &children[i]; break; }
    if (!slot) return 2;
    pid_t parent = getpid(), pid = fork();
    if (pid < 0) return 1;
    if (pid == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(125);
        int fd = open("/dev/null", O_RDWR);
        if (fd < 0) _exit(125);
        for (int i = 0; i <= 2; i++) if (dup2(fd, i) < 0) _exit(125);
        if (fd > 2) close(fd);
        // 不使用后台或强制标志，保留客户端寿命并遵循系统勿扰和震动设置。
        execl("/system/bin/cmd", "cmd", "vibrator_manager", "synced", "-d", "oppo_sidekey",
              "oneshot", "35", (char *)NULL);
        _exit(127);
    }
    *slot = (HapticChild){.pid = pid, .started = now};
    return 0;
}

int haptic_poll(uint64_t now)
{
    int failure = 0;
    for (int i = 0; i < HAPTIC_SLOTS; i++) {
        HapticChild *child = &children[i];
        if (!child->pid) continue;
        int status = 0;
        pid_t result = waitpid(child->pid, &status, WNOHANG);
        if (result < 0 && errno == EINTR) continue;
        if (result == 0) {
            if (!child->timed_out && now - child->started >= HAPTIC_TIMEOUT_MS) {
                (void)kill(child->pid, SIGKILL);
                child->timed_out = true;
                if (!child->cancelled) failure = 124;
            }
            continue;
        }
        if (!child->cancelled && !child->timed_out) {
            int code = result < 0 ? 125 : WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            if (code) failure = code;
        }
        *child = (HapticChild){0};
    }
    return failure;
}

bool haptic_active(void)
{
    for (int i = 0; i < HAPTIC_SLOTS; i++) if (children[i].pid > 0) return true;
    return false;
}

void haptic_stop(void)
{
    for (int i = 0; i < HAPTIC_SLOTS; i++) {
        if (children[i].pid <= 0) continue;
        children[i].cancelled = true;
        (void)kill(children[i].pid, SIGKILL);
    }
}
