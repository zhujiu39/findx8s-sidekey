#define _GNU_SOURCE
#include "surfing.h"
#include "menu.h"
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static char script[1024], log_path[1024], frame[2048], snapshot[2048] = "{\"state\":\"unknown\"}";
static pid_t watcher, controller;
static int output_fd = -1, control_result;
static size_t used;
static bool dropping, visible;
static uint64_t watch_deadline, control_deadline, updated, retry_at, control_finished;

bool surfing_init(const char *module, const char *directory)
{
    if (!module || !directory) return false;
    return snprintf(script, sizeof(script), "%s/scripts/surfing.sh", module) < (int)sizeof(script) &&
        snprintf(log_path, sizeof(log_path), "%s/surfing.log", directory) < (int)sizeof(log_path);
}

void surfing_close_descriptors(void)
{
    if (output_fd >= 0) close(output_fd);
    output_fd = -1;
}

static void stop_watcher(void)
{
    if (watcher > 0) (void)kill(-watcher, SIGKILL);
    surfing_close_descriptors();
}

void surfing_stop(void)
{
    stop_watcher();
    if (controller > 0) (void)kill(-controller, SIGKILL);
}

static pid_t launch(bool control)
{
    struct stat info;
    if (stat(log_path, &info) == 0 && info.st_size > 65536) {
        char backup[1030]; snprintf(backup, sizeof(backup), "%s.1", log_path);
        if (rename(log_path, backup) != 0) return -1;
    }
    int descriptors[2] = {-1, -1};
    if (!control && pipe2(descriptors, O_CLOEXEC) != 0) return -1;
    if (!control && fcntl(descriptors[0], F_SETFL, O_NONBLOCK) != 0) {
        close(descriptors[0]); close(descriptors[1]); return -1;
    }
    pid_t parent = getpid(), pid = fork();
    if (pid == 0) {
        (void)setpgid(0, 0);
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(125);
        int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
        int log_fd = open(log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
        if (null_fd < 0 || log_fd < 0 || dup2(null_fd, STDIN_FILENO) < 0 || dup2(log_fd, STDERR_FILENO) < 0 ||
            dup2(control ? null_fd : descriptors[1], STDOUT_FILENO) < 0) _exit(126);
        close(null_fd); close(log_fd);
        if (!control) { close(descriptors[0]); close(descriptors[1]); }
        menu_close_descriptors();
        execl("/system/bin/sh", "sh", script, control ? "toggle" : "watch", (char *)NULL);
        _exit(127);
    }
    if (!control) {
        close(descriptors[1]);
        if (pid < 0) close(descriptors[0]);
        else { output_fd = descriptors[0]; used = 0; dropping = false; }
    }
    if (pid > 0) (void)setpgid(pid, pid);
    return pid;
}

static void collect(uint64_t now)
{
    char buffer[2048];
    for (int batch = 0; output_fd >= 0 && batch < 4; batch++) {
        ssize_t count = read(output_fd, buffer, sizeof(buffer));
        if (count < 0 && (errno == EAGAIN || errno == EINTR)) return;
        if (count <= 0) { surfing_close_descriptors(); return; }
        for (ssize_t i = 0; i < count; i++) {
            char c = buffer[i];
            if (c == '\n') {
                if (!dropping && used >= 2 && frame[0] == '{' && frame[used - 1] == '}') {
                    frame[used] = 0; memcpy(snapshot, frame, used + 1); updated = now;
                }
                used = 0; dropping = false;
            } else if (!c || used >= sizeof(frame) - 1) dropping = true;
            else if (!dropping) frame[used++] = c;
        }
    }
}

void surfing_tick(bool active, uint64_t now)
{
    if (active != visible) {
        visible = active; updated = 0; strcpy(snapshot, "{\"state\":\"unknown\"}");
        if (!active) stop_watcher(); else retry_at = 0;
    }
    collect(now);
    if (watcher > 0) {
        int status = 0; pid_t result = waitpid(watcher, &status, WNOHANG);
        if (result == watcher || (result < 0 && errno != EINTR)) {
            stop_watcher(); watcher = 0; retry_at = now + 3000;
        } else if (now >= watch_deadline) stop_watcher();
    }
    if (controller > 0) {
        int status = 0; pid_t result = waitpid(controller, &status, WNOHANG);
        if (result == controller || (result < 0 && errno != EINTR)) {
            (void)kill(-controller, SIGKILL);
            control_result = result == controller && WIFEXITED(status) ? WEXITSTATUS(status) : 1;
            controller = 0; control_finished = now;
        } else if (now >= control_deadline) { (void)kill(-controller, SIGKILL); }
    }
    if (visible && !watcher && now >= retry_at) {
        watcher = launch(false);
        if (watcher < 0) { watcher = 0; retry_at = now + 3000; }
        else watch_deadline = now + 68000;
    }
}

bool surfing_select(uint64_t now)
{
    if (controller > 0 || !visible || !updated || now - updated > 5000 ||
        !(strstr(snapshot, "\"state\":\"on\"") || strstr(snapshot, "\"state\":\"off\"") || strstr(snapshot, "\"state\":\"stopped\""))) return false;
    controller = launch(true);
    if (controller < 0) { controller = 0; control_result = 1; return false; }
    control_result = 0; control_finished = 0; control_deadline = now + 55000;
    return true;
}

size_t surfing_reply(char *out, size_t capacity, uint64_t now)
{
    bool busy = controller > 0 || (visible && control_finished && updated <= control_finished && now - control_finished < 2000);
    const char *data = updated && now - updated <= 5000 ? snapshot : "{\"state\":\"unknown\",\"message\":\"正在读取状态\"}";
    int size = snprintf(out, capacity, "{\"busy\":%s,\"result\":%d,\"data\":%s}\n", busy ? "true" : "false", control_result, data);
    return size > 0 && (size_t)size < capacity ? (size_t)size : 0;
}
