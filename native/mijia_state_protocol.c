#include "mijia_state_protocol.h"
#include <string.h>

static bool hex_id(const char *value, size_t length)
{
    return value && strlen(value) == length && strspn(value, "0123456789abcdef") == length;
}

bool mijia_state_request(FILE *output, const char *session, const char ids[][33], uint32_t count)
{
    if (!output || !ids || count > MIJIA_BINDING_CAP || !hex_id(session, 64)) return false;
    for (uint32_t i = 0; i < count; i++) if (!hex_id(ids[i], 32)) return false;
    fprintf(output, "{\"op\":\"menu-states\",\"session\":\"%s\",\"ids\":[", session);
    for (uint32_t i = 0; i < count; i++) fprintf(output, "%s\"%s\"", i ? "," : "", ids[i]);
    fputs("]}", output);
    return !ferror(output);
}

bool mijia_control_request(FILE *output, const char *session, const char ids[][33], uint32_t count,
                           const char *id, const char *request_id)
{
    if (!output || !ids || !count || count > MIJIA_BINDING_CAP || !hex_id(session, 64) ||
        !hex_id(id, 32) || !hex_id(request_id, 32)) return false;
    bool visible = false;
    for (uint32_t i = 0; i < count; i++) {
        if (!hex_id(ids[i], 32)) return false;
        if (!strcmp(ids[i], id)) visible = true;
    }
    if (!visible) return false;
    fprintf(output, "{\"op\":\"menu-control\",\"session\":\"%s\",\"id\":\"%s\",\"requestId\":\"%s\",\"ids\":[", session, id, request_id);
    for (uint32_t i = 0; i < count; i++) fprintf(output, "%s\"%s\"", i ? "," : "", ids[i]);
    fputs("]}", output); return !ferror(output);
}

bool mijia_result_request(FILE *output, const char *session, const char *request_id)
{
    if (!output || !hex_id(session, 64) || !hex_id(request_id, 32)) return false;
    fprintf(output, "{\"op\":\"menu-result\",\"session\":\"%s\",\"requestId\":\"%s\"}", session, request_id);
    return !ferror(output);
}

int mijia_state_reply(const char *json, uint32_t count, char *states)
{
    if (!json || !states || count > MIJIA_BINDING_CAP) return -1;
    if (!strcmp(json, "{\"states\":\"P\"}")) return 0;
    const char *prefix = "{\"states\":\"D";
    size_t start = strlen(prefix);
    if (strlen(json) != start + count + 2 || strncmp(json, prefix, start) ||
        strcmp(json + start + count, "\"}") || strspn(json + start, "01?n") != count) return -1;
    memcpy(states, json + start, count); return 1;
}
