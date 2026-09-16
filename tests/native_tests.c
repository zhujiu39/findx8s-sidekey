#include "gesture.h"
#include "config.h"
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
    if (argc == 2) {
        FILE *file = fopen(argv[1], "rb");
        if (!file) return 2;
        char text[CONFIG_CAP], error[256];
        size_t length = fread(text, 1, sizeof(text) - 1, file);
        fclose(file); text[length] = 0;
        Config config;
        if (!config_parse(text, &config, error, sizeof(error))) return 1;
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
    assert(!strcmp(config.actions[2].argument, "echo hi"));
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
    puts("PASS: 9 gesture scenarios and strict config / hex validation");
    return 0;
}
