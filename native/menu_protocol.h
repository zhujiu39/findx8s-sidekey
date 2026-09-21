#ifndef SIDEKEY_MENU_PROTOCOL_H
#define SIDEKEY_MENU_PROTOCOL_H
#include <stdbool.h>
#include <stdint.h>
#define MENU_TOKEN_CAP 65
/* 仅允许 PING、CLOSE、SELECT 三种有限命令；不接收 Shell 或动作参数。 */
typedef enum { MENU_INVALID, MENU_PING, MENU_CLOSE, MENU_SELECT } MenuCommand;
MenuCommand menu_authorize(const char *line, const char *token, uint64_t expires,
                           uint64_t now, uint32_t count, uint32_t *index);
#endif
