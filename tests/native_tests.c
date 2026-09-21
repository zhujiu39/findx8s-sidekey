#include "gesture.h"
#include "config.h"
#include "menu_protocol.h"
#include "menu_launch.h"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <assert.h>
#include <stdio.h>
#include <string.h>

static GestureKind events[32];
static int count;
static void record(GestureKind kind, void *context)
{
    (void)context;
    assert(count < 32);
    events[count++] = kind;
}
static GestureState state;
static void reset(bool double_enabled)
{
    count = 0;
    gesture_init(&state, 600, 280, double_enabled, record, NULL);
}
int main(int argc, char **argv)
{
    if (argc == 2 || argc == 3) {
        FILE *file = fopen(argv[1], "rb");
        if (!file) return 2;
        char text[CONFIG_CAP], error[256];
        size_t length = fread(text, 1, sizeof(text) - 1, file);
        fclose(file); text[length] = 0;
        Config config;
        if (!config_parse(text, &config, error, sizeof(error))) return 1;
        if (argc == 3) {
            if (!config_write(argv[2], &config) || !config_read(argv[2], &config, error, sizeof(error))) return 3;
        }
        config_json(stdout, &config);
        return 0;
    }
    reset(false);
    gesture_event(&state, 1, 1000); gesture_event(&state, 0, 1100);
    assert(count == 1 && events[0] == GESTURE_SINGLE);

    reset(true);
    gesture_event(&state, 1, 1000); gesture_event(&state, 0, 1100);
    gesture_tick(&state, 1379); assert(count == 0);
    gesture_tick(&state, 1380); assert(count == 1 && events[0] == GESTURE_SINGLE);

    reset(true);
    gesture_event(&state, 1, 1000); gesture_event(&state, 0, 1100);
    gesture_event(&state, 1, 1200); gesture_event(&state, 0, 1300);
    gesture_tick(&state, 9999); assert(count == 1 && events[0] == GESTURE_DOUBLE);

    reset(true);
    gesture_event(&state, 1, 1000); gesture_tick(&state, 1599); assert(count == 0);
    gesture_tick(&state, 1600); gesture_event(&state, 2, 1700);
    gesture_tick(&state, 9000); gesture_event(&state, 0, 10000);
    assert(count == 1 && events[0] == GESTURE_LONG);

    reset(true);
    gesture_event(&state, 0, 900); gesture_event(&state, 1, 1000);
    gesture_event(&state, 1, 1100); gesture_event(&state, 0, 1700);
    assert(count == 1 && events[0] == GESTURE_LONG);

    reset(true);
    gesture_event(&state, 1, 1000); gesture_event(&state, 0, 1100);
    gesture_event(&state, 1, 1380); gesture_event(&state, 0, 1500);
    gesture_tick(&state, 1780);
    assert(count == 2 && events[0] == GESTURE_SINGLE && events[1] == GESTURE_SINGLE);

    reset(true);
    gesture_event(&state, 1, 1000); gesture_event(&state, 0, 1100);
    gesture_event(&state, 1, 1200); gesture_tick(&state, 1800);
    gesture_event(&state, 0, 2000);
    assert(count == 1 && events[0] == GESTURE_LONG);

    reset(false);
    gesture_event(&state, 1, UINT64_C(5000000000));
    gesture_event(&state, 0, UINT64_C(5000000100));
    assert(count == 1 && events[0] == GESTURE_SINGLE);

    reset(true);
    gesture_event(&state, 1, 1000); gesture_event(&state, 0, 1100);
    gesture_init(&state, 600, 280, true, record, NULL);
    gesture_tick(&state, 5000); assert(count == 0);

    const char *valid = "version=1\nenabled=1\nlong_ms=600\ndouble_ms=280\nsingle=home\ndouble=none\nlong=shell\nsingle_arg=\ndouble_arg=\nlong_arg=6563686f206869\n";
    Config config;
    char error[256], text[CONFIG_CAP], decoded[4];
    assert(config_parse(valid, &config, error, sizeof(error)));
    assert(config.enabled && config.actions[0].kind == ACTION_HOME);
    assert(config.haptic); /* 旧配置升级默认开启，其他动作不变。 */
    assert(!strcmp(config.actions[2].argument, "echo hi"));
    snprintf(text, sizeof(text), "%shaptic=0\n", valid);
    assert(config_parse(text, &config, error, sizeof(error)) && !config.haptic);
    assert(config.actions[0].kind == ACTION_HOME && !strcmp(config.actions[2].argument, "echo hi"));
    snprintf(text, sizeof(text), "%shaptic=1\n", valid);
    assert(config_parse(text, &config, error, sizeof(error)) && config.haptic);
    snprintf(text, sizeof(text), "%shaptic=2\n", valid);
    assert(!config_parse(text, &config, error, sizeof(error)));
    snprintf(text, sizeof(text), "%shaptic=0\nhaptic=1\n", valid);
    assert(!config_parse(text, &config, error, sizeof(error)));
    assert(!config_parse("version=1\n", &config, error, sizeof(error)));
    snprintf(text, sizeof(text), "%senabled=0\n", valid);
    assert(!config_parse(text, &config, error, sizeof(error)));
    strcpy(text, valid); char *p = strstr(text, "600"); memcpy(p, "001", 3);
    assert(!config_parse(text, &config, error, sizeof(error)));
    assert(!hex_decode("00", decoded, sizeof(decoded)));
    assert(!hex_decode("a", decoded, sizeof(decoded)));
    assert(!hex_decode("xxxx", decoded, sizeof(decoded)));
    assert(!hex_decode("41424344", decoded, sizeof(decoded)));
    assert(hex_decode("414243", decoded, sizeof(decoded)) && !strcmp(decoded, "ABC"));
    assert(config_parse(valid, &config, error, sizeof(error)));
    assert(config.menu_count == 0 && !config.menu_right && config.menu_position == 35);
    const char *menu = "menu_count=1\nmenu_side=left\nmenu_position=35\nmenu_0_name=e8aebee7bdae\nmenu_0_icon=e29a99efb88f\nmenu_0_action=torch\nmenu_0_arg=\n";
    snprintf(text, sizeof(text), "%s%s", valid, menu);
    assert(config_parse(text, &config, error, sizeof(error)));
    assert(config.menu_count == 1 && config.menu[0].action.kind == ACTION_TORCH);
    assert(!strcmp(config.menu[0].name, "设置"));
    const char *bad[] = {"menu_count=13\nmenu_side=left\nmenu_position=35\n",
        "menu_count=1\nmenu_side=left\nmenu_position=35\n", "menu_count=0\n",
        "menu_count=0\nmenu_side=top\nmenu_position=35\n", "menu_count=0\nmenu_side=right\nmenu_position=91\n",
        "menu_0_name=61\n"};
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        snprintf(text, sizeof(text), "%s%s", valid, bad[i]);
        assert(!config_parse(text, &config, error, sizeof(error)));
    }
    snprintf(text, sizeof(text), "%s%smenu_0_arg=\n", valid, menu);
    assert(!config_parse(text, &config, error, sizeof(error)));
    snprintf(text, sizeof(text), "%s%s", valid, menu);
    char *recursive = strstr(text, "torch"); memmove(recursive + 4, recursive + 5, strlen(recursive + 5) + 1); memcpy(recursive, "menu", 4);
    assert(!config_parse(text, &config, error, sizeof(error)));
    snprintf(text, sizeof(text), "%s%smenu_0_slot=10\n", valid, menu);
    assert(config_parse(text, &config, error, sizeof(error)) && config.menu[0].slot == 10);
    snprintf(text, sizeof(text), "%s%smenu_0_slot=12\n", valid, menu);
    assert(!config_parse(text, &config, error, sizeof(error)));
    snprintf(text, sizeof(text), "%s%smenu_0_slot=1\nmenu_0_slot=2\n", valid, menu);
    assert(!config_parse(text, &config, error, sizeof(error)));
    const char *grid = "menu_count=2\nmenu_side=left\nmenu_position=35\nmenu_0_name=61\nmenu_0_icon=\nmenu_0_action=app_freeform\nmenu_0_arg=636f6d2e617070\nmenu_0_slot=3\nmenu_1_name=62\nmenu_1_icon=\nmenu_1_action=torch\nmenu_1_arg=\nmenu_1_slot=10\n";
    snprintf(text, sizeof(text), "%s%s", valid, grid);
    assert(config_parse(text, &config, error, sizeof(error)));
    assert(config.menu[0].action.kind == ACTION_APP_FREEFORM && config.menu[1].slot == 10);
    char *duplicate = strstr(text, "menu_1_slot=10"); strcpy(duplicate, "menu_1_slot=3\n");
    assert(!config_parse(text, &config, error, sizeof(error)));
    const char *token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    uint32_t selected = 99;
    snprintf(text, sizeof(text), "SELECT %s 11", token);
    assert(menu_authorize(text, token, 60000, 59999, 12, &selected) == MENU_SELECT && selected == 11);
    assert(menu_authorize(text, token, 60000, 60000, 12, &selected) == MENU_INVALID);
    assert(menu_authorize(text, token, 60000, 1, 11, &selected) == MENU_INVALID);
    assert(menu_authorize(text, "", 60000, 1, 12, &selected) == MENU_INVALID);
    const char *tails[] = {"-1", "12", "00", "1;id", "1 ", "1\n", "99999999999999999999"};
    for (size_t i = 0; i < sizeof(tails) / sizeof(tails[0]); i++) {
        snprintf(text, sizeof(text), "SELECT %s %s", token, tails[i]);
        assert(menu_authorize(text, token, 60000, 1, 12, &selected) == MENU_INVALID);
    }
    snprintf(text, sizeof(text), "PING %s", token);
    assert(menu_authorize(text, token, 60000, 1, 0, &selected) == MENU_PING);
    text[5] = 'x'; assert(menu_authorize(text, token, 60000, 1, 0, &selected) == MENU_INVALID);
    snprintf(text, sizeof(text), "CLOSE %s", token);
    assert(menu_authorize(text, token, 60000, 1, 0, &selected) == MENU_CLOSE);
    puts("PASS: menu config migration, strict bounds, session token / expiry / index validation");
    MenuLaunch launch = {.deadline = 9000};
    assert(menu_launch_result(&launch, 1000) == -1);
    launch.command_done = true;
    assert(menu_launch_result(&launch, 2000) == -1); /* am 返回 0 不能替代组件握手。 */
    launch.ui_ready = true;
    assert(menu_launch_result(&launch, 8999) == 0);
    launch.command_done = false;
    assert(menu_launch_result(&launch, 8999) == -1); /* 握手也不能掩盖启动命令失败。 */
    launch.command_done = true; launch.error = 7;
    assert(menu_launch_result(&launch, 3000) == 7);
    launch.error = 0; launch.ui_ready = false;
    assert(menu_launch_result(&launch, 9000) == 124);
    launch.ui_ready = true;
    assert(menu_launch_result(&launch, 9000) == 124);
    puts("PASS: menu launch acknowledgement requires command success and UI handshake, with failure / deadline handling");
    puts("PASS: 9 gesture scenarios, strict config / hex validation and haptic upgrade compatibility");
    return 0;
}
