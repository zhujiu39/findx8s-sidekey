#ifndef SIDEKEY_HAPTIC_H
#define SIDEKEY_HAPTIC_H
#include <stdbool.h>
#include <stdint.h>

/* 请求 35 ms 短震动，非阻塞；返回 0 表示已启动请求，非物理震动成功确认。 */
int haptic_trigger(uint64_t now);
/* 回收子进程并处理 2 秒超时；非零为失败退出码。 */
int haptic_poll(uint64_t now);
bool haptic_active(void);
void haptic_stop(void);
#endif
