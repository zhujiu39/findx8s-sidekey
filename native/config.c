#define _POSIX_C_SOURCE 200809L
#include "config.h"
#include <ctype.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#ifdef _WIN32
#include <io.h>
#define fsync _commit
#endif

const char *const action_names[ACTION_COUNT] = {
    "none", "home", "back", "recents", "notifications", "quick_settings",
    "screenshot", "screen_off", "play_pause", "next", "previous", "volume_up",
    "volume_down", "mute", "camera", "app", "keycode", "shell", "torch", "menu"
};

void config_defaults(Config *c)
{
    if (!c) return;
    memset(c, 0, sizeof(*c));
    c->haptic = true;
    c->long_ms = 600;
    c->double_ms = 280;
    c->menu_position = 35;
}

static int hex_digit(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

bool hex_decode(const char *hex, char *out, size_t cap)
{
    if (!hex || !out || !cap) return false;
    size_t length = strlen(hex);
    if (length % 2 || length / 2 >= cap) return false;
    for (size_t i = 0; i < length / 2; i++) {
        int high = hex_digit(hex[i * 2]), low = hex_digit(hex[i * 2 + 1]);
        if (high < 0 || low < 0 || (high == 0 && low == 0)) return false;
        out[i] = (char)(high * 16 + low);
    }
    out[length / 2] = 0;
    return true;
}

static bool number(const char *text, uint32_t minimum, uint32_t maximum, uint32_t *out)
{
    if (!text || !*text) return false;
    for (const char *p = text; *p; p++) if (*p < '0' || *p > '9') return false;
    errno = 0;
    char *end = NULL;
    unsigned long value = strtoul(text, &end, 10);
    if (errno || !end || *end || value < minimum || value > maximum) return false;
    *out = (uint32_t)value;
    return true;
}

/**
 * @功能：严格解析配置；字段齐全、无重复且参数有效时才提交输出。
 * @日期：2026-09-16
 * @参数：[输入] text；[输出] config、error；[输入] error_cap。
 * @返回值：true 表示所有字段验证通过。
 */
bool config_parse(const char *text, Config *config, char *error, size_t error_cap)
{
    const char *keys[] = {"version", "enabled", "long_ms", "double_ms", "single", "double", "long", "single_arg", "double_arg", "long_arg", "haptic", "menu_count", "menu_side", "menu_position"};
    if (!text || !config || !error || !error_cap) return false;
    Config next;
    config_defaults(&next);
    char buffer[CONFIG_CAP];
    if (strlen(text) >= sizeof(buffer)) { snprintf(error, error_cap, "配置过长"); return false; }
    strcpy(buffer, text);
    unsigned int seen = 0;
    unsigned int menu_seen[MENU_CAP] = {0};
    char *save = NULL;
    for (char *line = strtok_r(buffer, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        char *equal = strchr(line, '=');
        if (!equal) goto invalid;
        *equal++ = 0;
        int key = -1;
        for (int i = 0; i < 14; i++) if (!strcmp(keys[i], line)) key = i;
        if (key < 0) {
            bool matched = false;
            const char *fields[] = {"name", "icon", "action", "arg"};
            for (uint32_t i = 0; i < MENU_CAP; i++) for (int f = 0; f < 4; f++) {
                char expected[40];
                snprintf(expected, sizeof(expected), "menu_%u_%s", i, fields[f]);
                if (strcmp(line, expected)) continue;
                if (menu_seen[i] & (1u << f)) goto invalid;
                menu_seen[i] |= 1u << f; matched = true;
                MenuItem *item = &next.menu[i];
                if (f == 0 && !hex_decode(equal, item->name, MENU_NAME_CAP)) goto invalid;
                if (f == 1 && !hex_decode(equal, item->icon, MENU_ICON_CAP)) goto invalid;
                if (f == 3 && !hex_decode(equal, item->action.argument, ARG_CAP)) goto invalid;
                if (f == 2) {
                    int action = -1;
                    for (int a = 0; a < ACTION_COUNT; a++) if (!strcmp(equal, action_names[a])) action = a;
                    if (action < 0 || action == ACTION_MENU) goto invalid;
                    item->action.kind = (ActionKind)action;
                }
            }
            if (!matched) goto invalid;
            continue;
        }
        if (seen & (1u << key)) goto invalid;
        seen |= 1u << key;
        uint32_t value = 0;
        if (key == 0) { if (strcmp(equal, "1")) goto invalid; }
        else if (key == 1) { if (!number(equal, 0, 1, &value)) goto invalid; next.enabled = value != 0; }
        else if (key == 2) { if (!number(equal, 250, 2000, &next.long_ms)) goto invalid; }
        else if (key == 3) { if (!number(equal, 150, 600, &next.double_ms)) goto invalid; }
        else if (key <= 6) {
            int action = -1;
            for (int i = 0; i < ACTION_COUNT; i++) if (!strcmp(equal, action_names[i])) action = i;
            if (action < 0) goto invalid;
            next.actions[key - 4].kind = (ActionKind)action;
        } else if (key == 10) {
            if (!number(equal, 0, 1, &value)) goto invalid;
            next.haptic = value != 0;
        } else if (key == 11) {
            if (!number(equal, 0, MENU_CAP, &next.menu_count)) goto invalid;
        } else if (key == 12) {
            if (strcmp(equal, "left") && strcmp(equal, "right")) goto invalid;
            next.menu_right = !strcmp(equal, "right");
        } else if (key == 13) {
            if (!number(equal, 10, 90, &next.menu_position)) goto invalid;
        } else if (!hex_decode(equal, next.actions[key - 7].argument, ARG_CAP)) goto invalid;
    }
    /* 旧版本没有 haptic 字段，升级时保留动作并使用默认开启的反馈。 */
    if ((seen & 1023) != 1023) goto invalid;
    if ((seen & (7u << 11)) && (seen & (7u << 11)) != (7u << 11)) goto invalid;
    for (uint32_t i = 0; i < MENU_CAP; i++) {
        if (menu_seen[i] != (i < next.menu_count ? 15u : 0u)) goto invalid;
        if (i < next.menu_count && !next.menu[i].name[0]) goto invalid;
    }
    for (uint32_t i = 0; i < 3 + next.menu_count; i++) {
        Action *a = i < 3 ? &next.actions[i] : &next.menu[i - 3].action;
        if (a->kind == ACTION_APP) {
            if (!*a->argument || !strchr(a->argument, '.')) goto invalid;
            for (const unsigned char *p = (unsigned char *)a->argument; *p; p++)
                if (!isalnum(*p) && *p != '.' && *p != '_') goto invalid;
        } else if (a->kind == ACTION_KEYCODE) {
            uint32_t keycode;
            if (!number(a->argument, 1, 2047, &keycode)) goto invalid;
        } else if (a->kind == ACTION_SHELL) {
            if (!*a->argument) goto invalid;
        } else if (*a->argument) goto invalid;
    }
    *config = next;
    error[0] = 0;
    return true;
invalid:
    snprintf(error, error_cap, "配置字段、动作参数或时间范围无效");
    return false;
}

bool config_read(const char *directory, Config *config, char *error, size_t cap)
{
    char path[1024], buffer[CONFIG_CAP];
    if (snprintf(path, sizeof(path), "%s/config.conf", directory) >= (int)sizeof(path)) return false;
    FILE *file = fopen(path, "r");
    if (!file) { snprintf(error, cap, "读取配置失败：%s", strerror(errno)); return false; }
    size_t n = fread(buffer, 1, sizeof(buffer) - 1, file);
    bool good = !ferror(file) && feof(file);
    fclose(file);
    buffer[n] = 0;
    if (!good || memchr(buffer, 0, n)) { snprintf(error, cap, "配置文件过长或包含空字符"); return false; }
    return config_parse(buffer, config, error, cap);
}

bool config_write(const char *directory, const Config *config)
{
    char path[1024], temp[1060];
    if (snprintf(path, sizeof(path), "%s/config.conf", directory) >= (int)sizeof(path)) return false;
    snprintf(temp, sizeof(temp), "%s.%ld.tmp", path, (long)getpid());
    FILE *file = fopen(temp, "w");
    if (!file) return false;
    fprintf(file, "version=1\nenabled=%d\nhaptic=%d\nlong_ms=%u\ndouble_ms=%u\n", config->enabled, config->haptic, config->long_ms, config->double_ms);
    const char *names[] = {"single", "double", "long"};
    for (int i = 0; i < 3; i++) fprintf(file, "%s=%s\n", names[i], action_names[config->actions[i].kind]);
    for (int i = 0; i < 3; i++) {
        fprintf(file, "%s_arg=", names[i]);
        for (const unsigned char *p = (const unsigned char *)config->actions[i].argument; *p; p++) fprintf(file, "%02x", *p);
        fputc('\n', file);
    }
    fprintf(file, "menu_count=%u\nmenu_side=%s\nmenu_position=%u\n", config->menu_count,
            config->menu_right ? "right" : "left", config->menu_position);
    for (uint32_t i = 0; i < config->menu_count; i++) {
        const MenuItem *item = &config->menu[i];
        const char *fields[] = {"name", "icon", "arg"};
        const char *values[] = {item->name, item->icon, item->action.argument};
        fprintf(file, "menu_%u_action=%s\n", i, action_names[item->action.kind]);
        for (int f = 0; f < 3; f++) {
            fprintf(file, "menu_%u_%s=", i, fields[f]);
            for (const unsigned char *p = (const unsigned char *)values[f]; *p; p++) fprintf(file, "%02x", *p);
            fputc('\n', file);
        }
    }
    bool good = !ferror(file) && fflush(file) == 0 && fsync(fileno(file)) == 0;
    if (fclose(file) != 0) good = false;
    if (good && rename(temp, path) == 0) return true;
    unlink(temp);
    return false;
}

void json_string(FILE *output, const char *text)
{
    fputc('"', output);
    if (text) for (const unsigned char *p = (const unsigned char *)text; *p; p++) {
        if (*p == '"' || *p == '\\') fprintf(output, "\\%c", *p);
        else if (*p < 32) fprintf(output, "\\u%04x", *p);
        else fputc(*p, output);
    }
    fputc('"', output);
}

void config_json(FILE *out, const Config *c)
{
    fprintf(out, "{\"enabled\":%s,\"haptic\":%s,\"long_ms\":%u,\"double_ms\":%u,\"actions\":[", c->enabled ? "true" : "false", c->haptic ? "true" : "false", c->long_ms, c->double_ms);
    for (int i = 0; i < 3; i++) {
        if (i) fputc(',', out);
        fputs("{\"type\":", out); json_string(out, action_names[c->actions[i].kind]);
        fputs(",\"argument\":", out); json_string(out, c->actions[i].argument); fputc('}', out);
    }
    fprintf(out, "],\"menu_side\":\"%s\",\"menu_position\":%u,\"menu\":[", c->menu_right ? "right" : "left", c->menu_position);
    for (uint32_t i = 0; i < c->menu_count; i++) {
        if (i) fputc(',', out);
        fputs("{\"name\":", out); json_string(out, c->menu[i].name);
        fputs(",\"icon\":", out); json_string(out, c->menu[i].icon);
        fputs(",\"type\":", out); json_string(out, action_names[c->menu[i].action.kind]);
        fputs(",\"argument\":", out); json_string(out, c->menu[i].action.argument);
        fputc('}', out);
    }
    fputs("]}", out);
}
