#define _GNU_SOURCE
#include "mijia_menu.h"
#include "mijia_state_protocol.h"
#include <arpa/inet.h>
#include <errno.h>
#include <poll.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static int connection = -1;
static bool connecting, done = true, control, acknowledged;
static uint64_t deadline, io_deadline, next_read, started;
static char current_session[65], control_id[33], log_directory[700];
static char ids[MIJIA_BINDING_CAP][33], states[MIJIA_BINDING_CAP];
static char readings[MIJIA_BINDING_CAP][MIJIA_READING_HEX_CAP];
static int16_t slots[MENU_CAP];
static uint32_t total, item_count;
static unsigned char request[9200], response[MIJIA_REPLY_CAP];
static size_t request_size, sent, received, expected;

static void trace(const char *stage, uint64_t now)
{
    if (!*log_directory) return;
    char path[768], backup[772];
    snprintf(path, sizeof(path), "%s/mijia-menu.log", log_directory);
    struct stat info;
    if (stat(path, &info) == 0 && info.st_size > 32768) {
        snprintf(backup, sizeof(backup), "%s.1", path);
        if (rename(path, backup) != 0) return;
    }
    FILE *log = fopen(path, "a");
    if (!log) return;
    time_t timestamp = time(NULL); struct tm tm; localtime_r(&timestamp, &tm);
    fprintf(log, "[%02d:%02d:%02d] %s %.8s %s，耗时 %llu ms\n", tm.tm_hour, tm.tm_min, tm.tm_sec,
            control ? "控制" : "展开", control_id, stage, (unsigned long long)(now - started));
    fclose(log);
}

static bool finish_request(FILE *out, bool good)
{
    long size = ftell(out); if (fclose(out) != 0) good = false;
    if (!good || size <= 0 || (size_t)size >= sizeof(request) - 4) return false;
    uint32_t length = htonl((uint32_t)size); memcpy(request, &length, 4); request_size = (size_t)size + 4;
    return true;
}

void mijia_menu_disconnect(void)
{
    if (connection >= 0) close(connection);
    connection = -1;
}

static void failed(void)
{
    mijia_menu_disconnect(); done = true;
    for (uint32_t i = 0; i < total; i++) if (states[i] == '~') states[i] = '?';
}

/**
 * @功能：为一次菜单展开建立全新状态请求，不沿用上次开关颜色。
 * @日期：2026-09-23
 * @参数：[输入] items、count - 会话快照；session - 能力令牌；directory - 私有数据目录；now - 单调毫秒。
 * @返回值：无
 * @使用说明：云端读取在米家服务执行，输入调度只运行非阻塞本地 IPC。
 */
void mijia_menu_reset(const MenuItem *items, uint32_t count, const char *session, const char *directory, uint64_t now)
{
    failed(); total = item_count = 0; control = false; acknowledged = false; control_id[0] = 0; current_session[0] = 0; started = now;
    memset(states, '?', sizeof(states));
    memset(readings, 0, sizeof(readings));
    if (!items || count > MENU_CAP || !session || strlen(session) != 64 || !directory || strlen(directory) >= sizeof(log_directory)) return;
    strcpy(current_session, session); strcpy(log_directory, directory);
    item_count = count;
    for (uint32_t i = 0; i < count; i++) {
        slots[i] = -1;
        if (items[i].action.kind != ACTION_MIJIA) continue;
        slots[i] = -2;
        const char *id = items[i].action.argument;
        if (strlen(id) != 32 || strspn(id, "0123456789abcdef") != 32) continue;
        uint32_t found = 0;
        while (found < total && strcmp(ids[found], id)) found++;
        if (found == total) {
            if (total == MIJIA_BINDING_CAP) continue;
            memcpy(ids[total++], id, 33);
        }
        slots[i] = (int16_t)found;
    }
    if (!total) return;
    FILE *out = fmemopen(request + 4, sizeof(request) - 4, "w");
    if (!out) return;
    if (!finish_request(out, mijia_state_request(out, session, ids, total))) return;
    memset(states, '~', sizeof(states)); done = false; deadline = now + 26000; next_read = now;
    trace("读取开始", now);
}

bool mijia_menu_select(uint32_t index, const char *request_id, uint64_t now)
{
    if (!done || index >= item_count || slots[index] < 0 || !request_id || strlen(request_id) != 32) return false;
    if (!strchr("01uan", states[slots[index]])) return false;
    FILE *out = fmemopen(request + 4, sizeof(request) - 4, "w");
    if (!out) return false;
    if (!finish_request(out, mijia_control_request(out, current_session, ids, total, ids[slots[index]], request_id))) return false;
    mijia_menu_disconnect(); strcpy(control_id, request_id); control = true; acknowledged = false; done = false; started = now;
    /* 只让所选项等待；其他设备维持颜色，服务返回后按设备合并更新。 */
    states[slots[index]] = '~';
    deadline = now + 55000; next_read = now; trace("直接提交到常驻服务", now); return true;
}

void mijia_menu_tick(const char *directory, uint64_t now)
{
    if (done) return;
    if (now >= deadline) { trace("等待结果超时", now); failed(); return; }
    if (connection < 0) {
        if (now < next_read) return;
        struct sockaddr_un address = {.sun_family = AF_UNIX};
        int size = snprintf(address.sun_path, sizeof(address.sun_path), "%s/mijia/control.sock", directory);
        if (size < 0 || (size_t)size >= sizeof(address.sun_path)) { failed(); return; }
        connection = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
        if (connection < 0) { failed(); return; }
        int result = connect(connection, (struct sockaddr *)&address, sizeof(address));
        if (result < 0 && errno != EINPROGRESS) { trace("本地服务连接失败", now); failed(); return; }
        connecting = true; io_deadline = now + 1500; sent = received = 0; expected = 4;
    }
    if (now >= io_deadline) { trace("本地通信超时", now); failed(); return; }
    if (connecting) {
        struct pollfd descriptor = {.fd = connection, .events = POLLOUT};
        int ready = poll(&descriptor, 1, 0);
        if (ready < 0 && errno != EINTR) { failed(); return; }
        if (ready <= 0) return;
        int error = 0; socklen_t size = sizeof(error);
        struct ucred peer; socklen_t peer_size = sizeof(peer);
        if (getsockopt(connection, SOL_SOCKET, SO_ERROR, &error, &size) || error ||
            getsockopt(connection, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) || peer.uid != 0) { failed(); return; }
        connecting = false;
    }
    if (sent < request_size) {
        ssize_t size = send(connection, request + sent, request_size - sent, MSG_NOSIGNAL);
        if (size < 0 && (errno == EAGAIN || errno == EINTR)) return;
        if (size <= 0) { trace("本地提交失败，不重发", now); failed(); return; }
        sent += (size_t)size; if (sent < request_size) return;
    }
    /* 每轮至多读包头和包体，半包留待下一次调度。 */
    for (int step = 0; step < 2; step++) {
        ssize_t size = recv(connection, response + received, expected - received, 0);
        if (size < 0 && (errno == EAGAIN || errno == EINTR)) return;
        if (size <= 0) { trace("本地响应中断，不重发", now); failed(); return; }
        received += (size_t)size; if (received < expected) return;
        if (expected == 4) {
            uint32_t length; memcpy(&length, response, 4); length = ntohl(length);
            if (!length || length >= sizeof(response) - 4) { failed(); return; }
            expected = 4 + length; continue;
        }
        response[received] = 0;
        int result = memchr(response + 4, 0, received - 4) ? -1 : mijia_status_reply((char *)response + 4, total, states, readings);
        if (result >= 0) acknowledged = true;
        mijia_menu_disconnect();
        if (result < 0) { trace("服务拒绝或响应无效，请查看米家服务日志", now); failed(); }
        else if (result == 1) { done = true; trace("最新状态已返回", now); }
        else {
            if (control) {
                FILE *out = fmemopen(request + 4, sizeof(request) - 4, "w");
                if (!out) { failed(); return; }
                if (!finish_request(out, mijia_result_request(out, current_session, control_id))) { failed(); return; }
            }
            next_read = now + 100; /* 只取本地任务结果；控制请求永远不重复发送。 */
        }
        return;
    }
}

int mijia_menu_wait_ms(void) { return connection >= 0 ? 10 : done ? 500 : 50; }
bool mijia_menu_submitting(void) { return control && !done && !acknowledged; }
char mijia_menu_state(uint32_t index)
{
    if (index >= item_count || slots[index] == -2) return '?';
    return slots[index] < 0 ? 'n' : states[slots[index]];
}

const char *mijia_menu_reading(uint32_t index)
{
    return index < item_count && slots[index] >= 0 ? readings[slots[index]] : "";
}
