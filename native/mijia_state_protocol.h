#ifndef SIDEKEY_MIJIA_STATE_PROTOCOL_H
#define SIDEKEY_MIJIA_STATE_PROTOCOL_H
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#define MIJIA_BINDING_CAP 256
bool mijia_state_request(FILE *output, const char *session, const char ids[][33], uint32_t count);
/* 返回 -1 无效、0 仍在读取、1 完成；仅完成时写入 states。 */
int mijia_state_reply(const char *json, uint32_t count, char *states);
#endif
