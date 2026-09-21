#ifndef SIDEKEY_MENU_H
#define SIDEKEY_MENU_H
#include "config.h"
/* 菜单客户端只提交一次性会话和索引，实际动作始终由守护进程持有。 */
typedef bool (*MenuSelect)(const Action *action);
bool menu_init(const char *directory);
int menu_poll(const Config *config, uint64_t now, MenuSelect selected, int32_t torch_pid);
void menu_cancel(void);
void menu_stop(void);
void menu_close_descriptors(void);
int menu_descriptor(void);
int menu_wait_ms(void);
int menu_request(const char *directory);
#endif
