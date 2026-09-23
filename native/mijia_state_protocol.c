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
    return mijia_status_reply(json, count, states, NULL);
}

/* 先验证整包再合并；每项最多 384 字节 UTF-8，十六进制传输不混入 JSON 或行协议控制符。 */
int mijia_status_reply(const char *json, uint32_t count, char *states,
                       char readings[][MIJIA_READING_HEX_CAP])
{
    if (!json || !states || count > MIJIA_BINDING_CAP) return -1;
    if (!strcmp(json, "{\"states\":\"P\"}")) return 0;
    const char *prefix = "{\"states\":\"D";
    size_t start = strlen(prefix);
    if (strlen(json) < start + count + 2 || strncmp(json, prefix, start) ||
        strspn(json + start, "01?noau-rsq") != count) return -1;
    const char *cursor = json + start + count;
    const char *fields[MIJIA_BINDING_CAP]; size_t sizes[MIJIA_BINDING_CAP];
    bool has_readings = *cursor == '|';
    if (has_readings) for (uint32_t i = 0; i < count; i++) {
        if (*cursor++ != '|') return -1;
        fields[i] = cursor; sizes[i] = strspn(cursor, "0123456789abcdef");
        if (sizes[i] >= MIJIA_READING_HEX_CAP || sizes[i] % 2) return -1;
        cursor += sizes[i];
    }
    if (strcmp(cursor, "\"}")) return -1;
    for (uint32_t i = 0; i < count; i++) if (json[start + i] != '-') {
        states[i] = json[start + i];
        if (readings) {
            size_t size = has_readings ? sizes[i] : 0;
            if (size) memcpy(readings[i], fields[i], size);
            readings[i][size] = 0;
        }
    }
    return 1;
}
