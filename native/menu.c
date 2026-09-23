#define _GNU_SOURCE
#include "menu.h"
#include "menu_protocol.h"
#include "menu_launch.h"
#include "mijia_menu.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

#define CLIENT_CAP 4
#define REQUEST_CAP 128
static int listener = -1;
static unsigned int port;
static char root_token[MENU_TOKEN_CAP], session[MENU_TOKEN_CAP], data_directory[700];
static uint64_t expires;
static pid_t launcher;
static MenuLaunch launch_state;
static int show_client = -1, launch_output = -1, launch_log = -1;
static size_t logged_bytes;
static MenuItem snapshot[MENU_CAP];
static uint32_t snapshot_count, snapshot_width, snapshot_gap;
static struct { int fd; size_t size, sent, response_size; char text[REQUEST_CAP], response[32768]; uint64_t until; } clients[CLIENT_CAP] = {{.fd = -1}, {.fd = -1}, {.fd = -1}, {.fd = -1}};

static size_t item_page(char *buffer, size_t capacity, uint32_t first)
{
    FILE *out = fmemopen(buffer, capacity, "w");
    if (!out) return 0;
    bool good = menu_write_items(out, snapshot, snapshot_count, first, snapshot_width, snapshot_gap);
    long size = ftell(out);
    if (fclose(out) != 0) good = false;
    return good && size > 0 && (size_t)size < capacity ? (size_t)size : 0;
}

static void finish_show(bool success)
{
    if (show_client >= 0) {
        const char *reply = success ? "OK\n" : "ERR\n";
        (void)send(show_client, reply, strlen(reply), MSG_NOSIGNAL);
        close(show_client); show_client = -1;
    }
}

/* 只有父进程接触私有日志；传给 Android Binder 的标准输出必须是管道。 */
static void collect_output(void)
{
    char buffer[2048];
    for (int reads = 0; launch_output >= 0 && reads < 16; reads++) {
        ssize_t n = read(launch_output, buffer, sizeof(buffer));
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) break;
        if (n <= 0) { close(launch_output); launch_output = -1; break; }
        size_t keep = (size_t)n;
        if (keep > 65536 - logged_bytes) keep = 65536 - logged_bytes;
        if (launch_log >= 0 && keep) {
            ssize_t written = write(launch_log, buffer, keep);
            if (written > 0) logged_bytes += (size_t)written;
        }
    }
}

static bool random_token(char *out)
{
    unsigned char bytes[32];
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t size = read(fd, bytes, sizeof(bytes)); close(fd);
    if (size != sizeof(bytes)) return false;
    for (size_t i = 0; i < sizeof(bytes); i++) snprintf(out + i * 2, 3, "%02x", bytes[i]);
    return true;
}

void menu_close_descriptors(void)
{
    mijia_menu_disconnect();
    if (show_client >= 0) close(show_client);
    if (launch_output >= 0) close(launch_output);
    if (launch_log >= 0) close(launch_log);
    show_client = launch_output = launch_log = -1;
    if (listener >= 0) close(listener);
    listener = -1;
    for (int i = 0; i < CLIENT_CAP; i++) {
        if (clients[i].fd >= 0) close(clients[i].fd);
        clients[i].fd = -1;
    }
}

void menu_cancel(void)
{
    finish_show(false);
    session[0] = 0; expires = 0; snapshot_count = 0;
    if (launcher > 0) (void)kill(-launcher, SIGKILL);
}

void menu_stop(void)
{
    menu_cancel(); menu_close_descriptors();
    if (*data_directory) {
        char path[1024]; snprintf(path, sizeof(path), "%s/menu.endpoint", data_directory);
        unlink(path);
    }
}

bool menu_init(const char *directory)
{
    for (int i = 0; i < CLIENT_CAP; i++) clients[i].fd = -1;
    if (!directory || strlen(directory) >= sizeof(data_directory)) return false;
    strcpy(data_directory, directory);
    listener = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    socklen_t size = sizeof(address);
    if (listener < 0 || bind(listener, (struct sockaddr *)&address, size) != 0 ||
        listen(listener, CLIENT_CAP) != 0 || getsockname(listener, (struct sockaddr *)&address, &size) != 0 ||
        !random_token(root_token)) { menu_stop(); return false; }
    port = ntohs(address.sin_port);
    char path[1024]; snprintf(path, sizeof(path), "%s/menu.endpoint", directory);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) { menu_stop(); return false; }
    bool good = dprintf(fd, "%u %s\n", port, root_token) > 0;
    close(fd);
    if (!good) menu_stop();
    return good;
}

/* am 在独立进程中启动；UI 和网络请求都不能阻塞输入事件调度。 */
static bool show_menu(const Config *config, uint64_t now)
{
    if (launcher > 0 || show_client >= 0) return false;
    menu_cancel();
    collect_output();
    if (launch_output >= 0) { close(launch_output); launch_output = -1; }
    if (launch_log >= 0) { close(launch_log); launch_log = -1; }
    snapshot_count = menu_snapshot(config, snapshot, MENU_CAP);
    snapshot_width = config->menu_width; snapshot_gap = config->menu_gap;
    /* 空菜单正常结束，不启动透明 Activity，也不等待不存在的组件握手。 */
    if (!snapshot_count) return true;
    if (!random_token(session)) return false;
    expires = now + 60000;
    int output_pipe[2];
    if (pipe2(output_pipe, O_CLOEXEC) != 0) { menu_cancel(); return false; }
    if (fcntl(output_pipe[0], F_SETFL, O_NONBLOCK) != 0) {
        close(output_pipe[0]); close(output_pipe[1]); menu_cancel(); return false;
    }
    launch_output = output_pipe[0];
    char path[1024]; snprintf(path, sizeof(path), "%s/menu-launch.log", data_directory);
    launch_log = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    logged_bytes = 0;
    pid_t parent = getpid();
    launcher = fork();
    if (launcher < 0) { close(output_pipe[1]); launcher = 0; menu_cancel(); return false; }
    if (launcher == 0) {
        (void)setpgid(0, 0);
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(125);
        int input = open("/dev/null", O_RDONLY | O_CLOEXEC);
        if (input < 0 || dup2(input, STDIN_FILENO) < 0 ||
            dup2(output_pipe[1], STDOUT_FILENO) < 0 || dup2(output_pipe[1], STDERR_FILENO) < 0) _exit(126);
        close(input); close(output_pipe[1]); menu_close_descriptors();
        char port_text[16], position[16];
        snprintf(port_text, sizeof(port_text), "%u", port);
        snprintf(position, sizeof(position), "%u", config->menu_position);
        execl("/system/bin/am", "am", "start", "--user", "current", "-W", "--activity-no-animation",
              "-n", "cn.sidekey.menu/.MenuActivity", "--ei", "port", port_text,
              "--es", "token", session,
              "--es", "side", config->menu_right ? "right" : "left", "--ei", "position", position, (char *)NULL);
        _exit(127);
    }
    close(output_pipe[1]);
    (void)setpgid(launcher, launcher);
    launch_state = (MenuLaunch){.deadline = now + 8000};
    return true;
}

static int torch_state(int32_t expected_pid)
{
    if (expected_pid <= 0) return -1;
    char path[1024], buffer[4096]; snprintf(path, sizeof(path), "%s/torch.json", data_directory);
    FILE *file = fopen(path, "r"); if (!file) return -1;
    size_t n = fread(buffer, 1, sizeof(buffer) - 1, file); fclose(file); buffer[n] = 0;
    char *pid = strstr(buffer, "\"pid\":");
    if (!pid || strtol(pid + 6, NULL, 10) != expected_pid ||
        !strstr(buffer, "\"known\":true") || !strstr(buffer, "\"available\":true")) return -1;
    if (strstr(buffer, "\"enabled\":true")) return 1;
    return strstr(buffer, "\"enabled\":false") ? 0 : -1;
}

int menu_poll(const Config *config, uint64_t now, MenuSelect selected, int32_t torch_pid)
{
    int failure = 0;
    collect_output();
    if (launcher > 0) {
        int status = 0;
        pid_t done = waitpid(launcher, &status, WNOHANG);
        if (done == launcher) {
            (void)kill(-launcher, SIGKILL); launcher = 0;
            launch_state.command_done = true;
            launch_state.error = WIFEXITED(status) ? WEXITSTATUS(status) : 1;
        } else if (done < 0 && errno != EINTR) { launcher = 0; launch_state.error = 1; }
    }
    if (show_client >= 0) {
        int result = menu_launch_result(&launch_state, now);
        if (result >= 0) {
            if (result) {
                if (launch_log >= 0) dprintf(launch_log, "\n菜单未就绪：代码 %d（命令完成=%d，组件握手=%d）。\n", result, launch_state.command_done, launch_state.ui_ready);
                failure = result; menu_cancel();
            } else finish_show(true);
        }
    }
    if (expires && now >= expires) { session[0] = 0; expires = 0; }
    if (*session) mijia_menu_tick(data_directory, now);
    else mijia_menu_disconnect();
    if (listener < 0) return failure;
    for (int i = 0; i < CLIENT_CAP; i++) {
        if (clients[i].fd < 0) {
            clients[i].fd = accept4(listener, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
            clients[i].size = clients[i].sent = clients[i].response_size = 0; clients[i].until = now + 1500;
        }
        if (clients[i].fd < 0) continue;
        if (clients[i].response_size) {
            ssize_t written = send(clients[i].fd, clients[i].response + clients[i].sent,
                                   clients[i].response_size - clients[i].sent, MSG_NOSIGNAL);
            if (written > 0) clients[i].sent += (size_t)written;
            if (clients[i].sent < clients[i].response_size && now < clients[i].until &&
                (written >= 0 || errno == EAGAIN || errno == EINTR)) continue;
            close(clients[i].fd); clients[i].fd = -1; continue;
        }
        ssize_t n = recv(clients[i].fd, clients[i].text + clients[i].size, REQUEST_CAP - 1 - clients[i].size, 0);
        if (n < 0 && (errno == EAGAIN || errno == EINTR) && now < clients[i].until) continue;
        if (n > 0) clients[i].size += (size_t)n;
        clients[i].text[clients[i].size] = 0;
        char *newline = memchr(clients[i].text, '\n', clients[i].size);
        bool accepted = false, ping = false;
        if (newline && newline == clients[i].text + clients[i].size - 1 && !memchr(clients[i].text, 0, clients[i].size)) {
            *newline = 0;
            char expected[96]; snprintf(expected, sizeof(expected), "SHOW %s", root_token);
            if (!strcmp(expected, clients[i].text)) {
                accepted = show_menu(config, now);
                if (accepted && snapshot_count) { show_client = clients[i].fd; clients[i].fd = -1; continue; }
            }
            else {
                uint32_t index = 0;
                MenuCommand command = menu_authorize(clients[i].text, session, expires, now, snapshot_count, &index);
                if (command == MENU_PING) {
                    if (!launch_state.ui_ready) mijia_menu_reset(snapshot, snapshot_count, session, now);
                    accepted = true; ping = true; launch_state.ui_ready = true;
                }
                else if (command == MENU_ITEMS || command == MENU_STATES) {
                    if (command == MENU_ITEMS)
                        clients[i].response_size = item_page(clients[i].response, sizeof(clients[i].response), index);
                    else {
                        memcpy(clients[i].response, "STATES ", 7);
                        for (uint32_t item = 0; item < snapshot_count; item++) clients[i].response[7 + item] = mijia_menu_state(item);
                        clients[i].response[7 + snapshot_count] = '\n'; clients[i].response_size = 8 + snapshot_count;
                    }
                    if (clients[i].response_size) {
                        ssize_t written = send(clients[i].fd, clients[i].response, clients[i].response_size, MSG_NOSIGNAL);
                        if (written > 0) clients[i].sent = (size_t)written;
                        if (clients[i].sent == clients[i].response_size) { close(clients[i].fd); clients[i].fd = -1; }
                        continue;
                    }
                }
                else if (command == MENU_CLOSE) { session[0] = 0; expires = 0; accepted = true; if (!launch_state.ui_ready) launch_state.error = 1; }
                else if (command == MENU_SELECT) {
                    session[0] = 0; expires = 0;
                    accepted = selected && selected(&snapshot[index].action);
                }
            }
        } else if (n > 0 && !newline && clients[i].size < REQUEST_CAP - 1 && now < clients[i].until) continue;
        char response[32];
        int response_size = ping ? snprintf(response, sizeof(response), "OK %d\n", torch_state(torch_pid)) :
            snprintf(response, sizeof(response), "%s\n", accepted ? "OK" : "ERR");
        (void)send(clients[i].fd, response, (size_t)response_size, MSG_NOSIGNAL);
        close(clients[i].fd); clients[i].fd = -1;
    }
    return failure;
}

int menu_descriptor(void) { return listener; }
int menu_wait_ms(void)
{
    for (int i = 0; i < CLIENT_CAP; i++) if (clients[i].fd >= 0) return 5;
    if (launcher > 0 || show_client >= 0 || launch_output >= 0) return 50;
    return mijia_menu_wait_ms();
}

int menu_request(const char *directory)
{
    char path[1024], token[MENU_TOKEN_CAP]; unsigned int requested_port = 0;
    snprintf(path, sizeof(path), "%s/menu.endpoint", directory);
    FILE *file = fopen(path, "r");
    if (!file) return 1;
    int count = fscanf(file, "%u %64s", &requested_port, token); fclose(file);
    if (count != 2 || requested_port < 1 || requested_port > 65535 || strlen(token) != 64) return 1;
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return 1;
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK), .sin_port = htons((uint16_t)requested_port)};
    struct pollfd poll_fd = {.fd = fd, .events = POLLOUT};
    int result = connect(fd, (struct sockaddr *)&address, sizeof(address));
    if (result != 0 && errno != EINPROGRESS) { close(fd); return 1; }
    int error = 0; socklen_t length = sizeof(error);
    if (poll(&poll_fd, 1, 500) <= 0 || getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) != 0 || error) { close(fd); return 1; }
    char request[96]; int size = snprintf(request, sizeof(request), "SHOW %s\n", token);
    if (send(fd, request, (size_t)size, MSG_NOSIGNAL) != size) { close(fd); return 1; }
    poll_fd.events = POLLIN;
    char response[8] = {0};
    bool good = poll(&poll_fd, 1, 9000) > 0 && recv(fd, response, sizeof(response) - 1, 0) == 3 && !strcmp(response, "OK\n");
    close(fd); return good ? 0 : 1;
}
