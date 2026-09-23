#ifndef SIDEKEY_MENU_PROTOCOL_H
#define SIDEKEY_MENU_PROTOCOL_H
#include <stdbool.h>
#include <stdint.h>
#include "config.h"
#define MENU_TOKEN_CAP 65
/* ITEMS 分页读取展示信息；所有请求绑定一次性会话，不接收动作参数。 */
typedef enum { MENU_INVALID, MENU_PING, MENU_CLOSE, MENU_SELECT, MENU_ITEMS, MENU_STATES, MENU_READINGS } MenuCommand;
uint32_t menu_snapshot(const Config *config, MenuItem *items, uint32_t capacity);
bool menu_write_items(FILE *output, const MenuItem *items, uint32_t count, uint32_t first,
                      uint32_t width, uint32_t gap);
MenuCommand menu_authorize(const char *line, const char *token, uint64_t expires,
                           uint64_t now, uint32_t count, uint32_t *index);
#endif
