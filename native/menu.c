#define _GNU_SOURCE
#include "menu.h"
#include "menu_protocol.h"
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
static uint64_t expires, launcher_started;
static pid_t launcher;
static Action snapshot[MENU_CAP];
static uint32_t snapshot_count;
static struct { int fd; size_t size; char text[REQUEST_CAP]; uint64_t until; } clients[CLIENT_CAP] = {{.fd = -1}, {.fd = -1}, {.fd = -1}, {.fd = -1}};

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
    if (listener >= 0) close(listener);
    listener = -1;
    for (int i = 0; i < CLIENT_CAP; i++) {
        if (clients[i].fd >= 0) close(clients[i].fd);
        clients[i].fd = -1;
    }
}

void menu_cancel(void)
{
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
    if (launcher > 0) return false;
    menu_cancel();
    if (!random_token(session)) return false;
    snapshot_count = config->menu_count;
    for (uint32_t i = 0; i < snapshot_count; i++) snapshot[i] = config->menu[i].action;
    expires = now + 60000;
    char labels[10000];
    FILE *out = fmemopen(labels, sizeof(labels), "w");
    if (!out) { menu_cancel(); return false; }
    fputc('[', out);
    for (uint32_t i = 0; i < config->menu_count; i++) {
        if (i) fputc(',', out);
        fprintf(out, "{\"slot\":%u,\"type\":\"%s\",\"name\":", config->menu[i].slot, action_names[config->menu[i].action.kind]); json_string(out, config->menu[i].name);
        fputs(",\"icon\":", out); json_string(out, config->menu[i].icon); fputc('}', out);
    }
    fputc(']', out);
    bool good = !ferror(out);
    if (fclose(out) != 0) good = false;
    if (!good) { menu_cancel(); return false; }
    pid_t parent = getpid();
    launcher = fork();
    if (launcher < 0) { launcher = 0; menu_cancel(); return false; }
    if (launcher == 0) {
        (void)setpgid(0, 0);
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(125);
        menu_close_descriptors();
        char path[1024]; snprintf(path, sizeof(path), "%s/menu-launch.log", data_directory);
        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (fd >= 0) { (void)dup2(fd, 1); (void)dup2(fd, 2); close(fd); }
        char port_text[16], position[16];
        snprintf(port_text, sizeof(port_text), "%u", port);
        snprintf(position, sizeof(position), "%u", config->menu_position);
        execl("/system/bin/am", "am", "start", "--user", "current", "-W", "--activity-no-animation",
              "-n", "cn.sidekey.menu/.MenuActivity", "--ei", "port", port_text,
              "--es", "token", session, "--es", "items", labels,
              "--es", "side", config->menu_right ? "right" : "left", "--ei", "position", position, (char *)NULL);
        _exit(127);
    }
    (void)setpgid(launcher, launcher);
    launcher_started = now;
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
    if (launcher > 0) {
        int status = 0;
        pid_t done = waitpid(launcher, &status, WNOHANG);
        if (done == launcher) {
            (void)kill(-launcher, SIGKILL); launcher = 0;
            if (!WIFEXITED(status) || WEXITSTATUS(status)) { menu_cancel(); failure = 1; }
        } else if (done < 0 && errno != EINTR) { launcher = 0; menu_cancel(); failure = 1; }
        else if (now - launcher_started >= 8000) { if (expires) failure = 124; menu_cancel(); }
    }
    if (expires && now >= expires) { session[0] = 0; expires = 0; }
    if (listener < 0) return failure;
    for (int i = 0; i < CLIENT_CAP; i++) {
        if (clients[i].fd < 0) {
            clients[i].fd = accept4(listener, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
            clients[i].size = 0; clients[i].until = now + 1500;
        }
        if (clients[i].fd < 0) continue;
        ssize_t n = recv(clients[i].fd, clients[i].text + clients[i].size, REQUEST_CAP - 1 - clients[i].size, 0);
        if (n < 0 && (errno == EAGAIN || errno == EINTR) && now < clients[i].until) continue;
        if (n > 0) clients[i].size += (size_t)n;
        clients[i].text[clients[i].size] = 0;
        char *newline = memchr(clients[i].text, '\n', clients[i].size);
        bool accepted = false, ping = false;
        if (newline && newline == clients[i].text + clients[i].size - 1 && !memchr(clients[i].text, 0, clients[i].size)) {
            *newline = 0;
            char expected[96]; snprintf(expected, sizeof(expected), "SHOW %s", root_token);
            if (!strcmp(expected, clients[i].text)) accepted = show_menu(config, now);
            else {
                uint32_t index = 0;
                MenuCommand command = menu_authorize(clients[i].text, session, expires, now, snapshot_count, &index);
                if (command == MENU_PING) { accepted = true; ping = true; }
                else if (command == MENU_CLOSE) { session[0] = 0; expires = 0; accepted = true; }
                else if (command == MENU_SELECT) {
                    session[0] = 0; expires = 0;
                    accepted = selected && selected(&snapshot[index]);
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
    if (launcher > 0) return 50;
    for (int i = 0; i < CLIENT_CAP; i++) if (clients[i].fd >= 0) return 50;
    return 500;
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
    if (poll(&poll_fd, 1, 1500) <= 0 || getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) != 0 || error) { close(fd); return 1; }
    char request[96]; int size = snprintf(request, sizeof(request), "SHOW %s\n", token);
    if (send(fd, request, (size_t)size, MSG_NOSIGNAL) != size) { close(fd); return 1; }
    poll_fd.events = POLLIN;
    char response[8] = {0};
    bool good = poll(&poll_fd, 1, 3000) > 0 && recv(fd, response, sizeof(response) - 1, 0) == 3 && !strcmp(response, "OK\n");
    close(fd); return good ? 0 : 1;
}
