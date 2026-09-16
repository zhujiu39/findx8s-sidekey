#ifndef SIDEKEY_CONFIG_H
#define SIDEKEY_CONFIG_H
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>

#define ARG_CAP 513
#define CONFIG_CAP 8192
typedef enum {
    ACTION_NONE, ACTION_HOME, ACTION_BACK, ACTION_RECENTS, ACTION_NOTIFICATIONS,
    ACTION_QUICK_SETTINGS, ACTION_SCREENSHOT, ACTION_SCREEN_OFF, ACTION_PLAY_PAUSE,
    ACTION_NEXT, ACTION_PREVIOUS, ACTION_VOLUME_UP, ACTION_VOLUME_DOWN,
    ACTION_MUTE, ACTION_CAMERA, ACTION_APP, ACTION_KEYCODE, ACTION_SHELL,
    ACTION_COUNT
} ActionKind;
typedef struct { ActionKind kind; char argument[ARG_CAP]; } Action;
typedef struct { bool enabled; uint32_t long_ms, double_ms; Action actions[3]; } Config;
extern const char *const action_names[ACTION_COUNT];
void config_defaults(Config *config);
bool config_parse(const char *text, Config *config, char *error, size_t error_cap);
bool config_read(const char *directory, Config *config, char *error, size_t cap);
bool config_write(const char *directory, const Config *config);
void config_json(FILE *output, const Config *config);
void json_string(FILE *output, const char *text);
bool hex_decode(const char *hex, char *output, size_t cap);
#endif
