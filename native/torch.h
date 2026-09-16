#ifndef SIDEKEY_TORCH_H
#define SIDEKEY_TORCH_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

/* 主循环每次调度调用；不等待 Java 初始化或相机 IPC。 */
pid_t torch_supervise(bool wanted, const char *module, const char *data, uint64_t now);
void torch_stop(void);
/* 仅在动作子进程调用；最多等待 8 秒，不重发已发送的切换请求。 */
int torch_toggle(const char *data, char *message, size_t cap);
#endif
