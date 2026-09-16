#include "gesture.h"
#include <string.h>

void gesture_init(GestureState *s, uint32_t long_ms, uint32_t double_ms,
                  bool double_enabled, GestureCallback callback, void *context)
{
    if (!s) return;
    memset(s, 0, sizeof(*s));
    s->long_ms = long_ms;
    s->double_ms = double_ms;
    s->double_enabled = double_enabled;
    s->callback = callback;
    s->context = context;
}

static void emit(GestureState *s, GestureKind kind)
{
    if (s->callback) s->callback(kind, s->context);
}

void gesture_tick(GestureState *s, uint64_t now)
{
    if (!s) return;
    if (s->down && !s->long_fired && now >= s->down_ms + s->long_ms) {
        s->long_fired = true;
        s->waiting = false;
        emit(s, GESTURE_LONG);
    }
    if (!s->down && s->waiting && now >= s->deadline_ms) {
        s->waiting = false;
        emit(s, GESTURE_SINGLE);
    }
}

void gesture_event(GestureState *s, int32_t value, uint64_t now)
{
    if (!s) return;
    gesture_tick(s, now);
    if (value == 1 && !s->down) {
        s->second = s->waiting;
        s->waiting = false;
        s->down = true;
        s->long_fired = false;
        s->down_ms = now;
    } else if (value == 0 && s->down) {
        s->down = false;
        if (s->long_fired) return;
        if (s->second) {
            s->second = false;
            emit(s, GESTURE_DOUBLE);
        } else if (s->double_enabled) {
            s->waiting = true;
            s->deadline_ms = now + s->double_ms;
        } else {
            emit(s, GESTURE_SINGLE);
        }
    }
    /* 值 2 是内核重复事件；长按由时间判断，不依赖重复上报。 */
}

uint64_t gesture_deadline(const GestureState *s)
{
    if (!s) return UINT64_MAX;
    if (s->down && !s->long_fired) return s->down_ms + s->long_ms;
    if (s->waiting) return s->deadline_ms;
    return UINT64_MAX;
}
