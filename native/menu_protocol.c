#include "menu_protocol.h"
#include <stdio.h>
#include <string.h>
MenuCommand menu_authorize(const char *line, const char *token, uint64_t expires,
                           uint64_t now, uint32_t count, uint32_t *index)
{
    if (!line || !token || !index || strlen(token) != 64 || now >= expires) return MENU_INVALID;
    char expected[96];
    snprintf(expected, sizeof(expected), "PING %s", token);
    if (!strcmp(line, expected)) return MENU_PING;
    snprintf(expected, sizeof(expected), "CLOSE %s", token);
    if (!strcmp(line, expected)) return MENU_CLOSE;
    for (uint32_t i = 0; i <= count; i += 16) {
        snprintf(expected, sizeof(expected), "ITEMS %s %u", token, i);
        if (!strcmp(line, expected)) { *index = i; return MENU_ITEMS; }
    }
    for (uint32_t i = 0; i < count; i++) {
        snprintf(expected, sizeof(expected), "SELECT %s %u", token, i);
        if (!strcmp(line, expected)) { *index = i; return MENU_SELECT; }
    }
    return MENU_INVALID;
}
