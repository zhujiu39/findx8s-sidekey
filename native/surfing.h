#ifndef SIDEKEY_SURFING_H
#define SIDEKEY_SURFING_H
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
/* 仅菜单可见时采集；已提交的启停独立完成，所有工作进程均有期限。 */
bool surfing_init(const char *module, const char *directory);
void surfing_tick(bool visible, uint64_t now);
bool surfing_select(uint64_t now);
size_t surfing_reply(char *output, size_t capacity, uint64_t now);
void surfing_close_descriptors(void);
void surfing_stop(void);
#endif
