#ifndef SIDEKEY_MIJIA_STATE_PROTOCOL_H
#define SIDEKEY_MIJIA_STATE_PROTOCOL_H
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#define MIJIA_BINDING_CAP 256
bool mijia_state_request(FILE *output, const char *session, const char ids[][33], uint32_t count);
bool mijia_control_request(FILE *output, const char *session, const char ids[][33], uint32_t count,
                           const char *id, const char *request_id);
bool mijia_result_request(FILE *output, const char *session, const char *request_id);
/* 返回 -1 无效、0 仍在读取、1 完成；完成时合并状态，'-' 保留未操作设备原值。 */
int mijia_state_reply(const char *json, uint32_t count, char *states);
#endif
