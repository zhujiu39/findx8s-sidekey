#ifndef SIDEKEY_MIJIA_MENU_H
#define SIDEKEY_MIJIA_MENU_H
#include "config.h"
void mijia_menu_reset(const MenuItem *items, uint32_t count, const char *session, const char *directory, uint64_t now);
/* 只提交一次控制；后续 IPC 仅查询该请求的本地结果，禁止重新发送控制。 */
bool mijia_menu_select(uint32_t index, const char *request_id, uint64_t now);
bool mijia_menu_submitting(void);
void mijia_menu_disconnect(void);
void mijia_menu_tick(const char *directory, uint64_t now);
int mijia_menu_wait_ms(void);
char mijia_menu_state(uint32_t index);
const char *mijia_menu_reading(uint32_t index);
#endif
