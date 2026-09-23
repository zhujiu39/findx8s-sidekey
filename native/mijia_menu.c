#define _GNU_SOURCE
#include "mijia_menu.h"
#include "mijia_state_protocol.h"
#include <arpa/inet.h>
#include <errno.h>
#include <poll.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static int connection = -1;
static bool connecting, done = true;
static uint64_t deadline, io_deadline, next_read;
static char ids[MIJIA_BINDING_CAP][33], states[MIJIA_BINDING_CAP];
static int16_t slots[MENU_CAP];
static uint32_t total, item_count;
static unsigned char request[9200], response[512];
static size_t request_size, sent, received, expected;

void mijia_menu_disconnect(void)
{
    if (connection >= 0) close(connection);
    connection = -1;
}

static void failed(void)
{
    mijia_menu_disconnect(); done = true;
    memset(states, '?', sizeof(states));
}

/**
 * @功能：为一次菜单展开建立全新状态请求，不沿用上次开关颜色。
 * @日期：2026-09-23
 * @参数：[输入] items、count - 会话快照；session - 能力令牌；now - 单调毫秒。
 * @返回值：无
 * @使用说明：云端读取在米家服务执行，输入调度只运行非阻塞本地 IPC。
 */
void mijia_menu_reset(const MenuItem *items, uint32_t count, const char *session, uint64_t now)
{
    failed(); total = item_count = 0;
    if (!items || count > MENU_CAP) return;
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
    bool good = mijia_state_request(out, session, ids, total);
    long size = ftell(out); if (fclose(out) != 0) good = false;
    if (!good || size <= 0 || (size_t)size >= sizeof(request) - 4) return;
    uint32_t length = htonl((uint32_t)size); memcpy(request, &length, 4); request_size = (size_t)size + 4;
    memset(states, '~', sizeof(states)); done = false; deadline = now + 26000; next_read = now;
}

void mijia_menu_tick(const char *directory, uint64_t now)
{
    if (done) return;
    if (now >= deadline) { failed(); return; }
    if (connection < 0) {
        if (now < next_read) return;
        struct sockaddr_un address = {.sun_family = AF_UNIX};
        int size = snprintf(address.sun_path, sizeof(address.sun_path), "%s/mijia/control.sock", directory);
        if (size < 0 || (size_t)size >= sizeof(address.sun_path)) { failed(); return; }
        connection = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
        if (connection < 0) { failed(); return; }
        int result = connect(connection, (struct sockaddr *)&address, sizeof(address));
        if (result < 0 && errno != EINPROGRESS) { failed(); return; }
        connecting = true; io_deadline = now + 1500; sent = received = 0; expected = 4;
    }
    if (now >= io_deadline) { failed(); return; }
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
        if (size <= 0) { failed(); return; }
        sent += (size_t)size; if (sent < request_size) return;
    }
    /* 每轮至多读包头和包体，半包留待下一次调度。 */
    for (int step = 0; step < 2; step++) {
        ssize_t size = recv(connection, response + received, expected - received, 0);
        if (size < 0 && (errno == EAGAIN || errno == EINTR)) return;
        if (size <= 0) { failed(); return; }
        received += (size_t)size; if (received < expected) return;
        if (expected == 4) {
            uint32_t length; memcpy(&length, response, 4); length = ntohl(length);
            if (!length || length >= sizeof(response) - 4) { failed(); return; }
            expected = 4 + length; continue;
        }
        response[received] = 0;
        int result = memchr(response + 4, 0, received - 4) ? -1 : mijia_state_reply((char *)response + 4, total, states);
        mijia_menu_disconnect();
        if (result < 0) failed();
        else if (result == 1) done = true;
        else next_read = now + 500; /* 仅取同一任务的本地结果，不重发云端读取。 */
        return;
    }
}

int mijia_menu_wait_ms(void) { return connection >= 0 ? 20 : 500; }
char mijia_menu_state(uint32_t index)
{
    if (index >= item_count || slots[index] == -2) return '?';
    return slots[index] < 0 ? 'n' : states[slots[index]];
}
