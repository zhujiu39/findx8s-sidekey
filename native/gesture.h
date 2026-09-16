#ifndef SIDEKEY_GESTURE_H
#define SIDEKEY_GESTURE_H
#include <stdbool.h>
#include <stdint.h>

typedef enum { GESTURE_SINGLE, GESTURE_DOUBLE, GESTURE_LONG } GestureKind;
typedef void (*GestureCallback)(GestureKind kind, void *context);
typedef struct {
    bool down, long_fired, waiting, second;
    uint64_t down_ms, deadline_ms;
    uint32_t long_ms, double_ms;
    bool double_enabled;
    GestureCallback callback;
    void *context;
} GestureState;

/**
 * @功能：初始化单键手势状态，使用调用者的单调毫秒时间。
 * @日期：2026-09-16
 * @参数：[输出] state；[输入] 长按阈值、双击间隔、双击开关、回调及上下文。
 * @返回值：无
 */
void gesture_init(GestureState *state, uint32_t long_ms, uint32_t double_ms,
                  bool double_enabled, GestureCallback callback, void *context);
void gesture_event(GestureState *state, int32_t value, uint64_t now_ms);
void gesture_tick(GestureState *state, uint64_t now_ms);
uint64_t gesture_deadline(const GestureState *state);
#endif
