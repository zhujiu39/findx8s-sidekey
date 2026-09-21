#include "menu_protocol.h"
#include <stdio.h>
#include <string.h>

/* 压紧展示索引，避免隐藏空动作后点击项与 Root 执行动作错位。 */
uint32_t menu_snapshot(const Config *config, MenuItem *items, uint32_t capacity)
{
    if (!config || !items || capacity < config->menu_count || config->menu_count > MENU_CAP) return 0;
    uint32_t count = 0;
    for (uint32_t i = 0; i < config->menu_count; i++) {
        if (config->menu[i].action.kind == ACTION_NONE) continue;
        items[count] = config->menu[i];
        /* 兼容旧版普通应用项；菜单应用仍统一走已验证的 ColorOS 小窗路径。 */
        if (items[count].action.kind == ACTION_APP) items[count].action.kind = ACTION_APP_FREEFORM;
        count++;
    }
    return count;
}

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
