#define _GNU_SOURCE
#include "config.h"
#include "gesture.h"
#include "torch.h"
#include "haptic.h"
#include "menu.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define TARGET_KEY 735
#define LOG_LIMIT 65536
#define ACTION_TIMEOUT_MS 10000
#define QUEUE_CAP 4
static const char *const gesture_names[] = {"single", "double", "long"};
static volatile sig_atomic_t stopping;
static char data_dir[700], module_dir[700], device_path[128], last_error[256];
static char last_gesture[24], last_action[32];
static Config config;
static GestureState gesture;
static uint32_t gesture_count;
static int32_t last_result;
static int input_fd = -1, lock_fd = -1;
static pid_t action_pid;
static uint64_t action_started;
static int32_t action_result_override;
static Action queue[QUEUE_CAP];
static uint32_t queue_size;
static bool daemon_context;
static pid_t torch_pid;

static uint64_t monotonic_ms(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static void path_for(char *out, size_t cap, const char *name)
{
    snprintf(out, cap, "%s/%s", data_dir, name);
}

static void log_event(const char *format, ...)
{
    if (!*data_dir) return;
    char path[1024], backup[1050];
    path_for(path, sizeof(path), "events.log");
    struct stat st;
    if (stat(path, &st) == 0 && st.st_size > LOG_LIMIT) {
        snprintf(backup, sizeof(backup), "%s.1", path);
        if (rename(path, backup) != 0) return;
    }
    FILE *file = fopen(path, "a");
    if (!file) return;
    time_t now = time(NULL);
    struct tm tm;
    localtime_r(&now, &tm);
    char timestamp[40];
    strftime(timestamp, sizeof(timestamp), "%m-%d %H:%M:%S", &tm);
    fprintf(file, "[%s] ", timestamp);
    va_list args;
    va_start(args, format);
    vfprintf(file, format, args);
    va_end(args);
    fputc('\n', file);
    fclose(file);
}

static void write_status(void)
{
    if (!daemon_context) return;
    char path[1024], temp[1050];
    path_for(path, sizeof(path), "status.json");
    snprintf(temp, sizeof(temp), "%s.tmp", path);
    FILE *file = fopen(temp, "w");
    if (!file) return;
    fprintf(file, "{\"pid\":%ld,\"grabbed\":%s,\"keycode\":735,\"device\":", (long)getpid(), input_fd >= 0 ? "true" : "false");
    json_string(file, device_path);
    fputs(",\"error\":", file); json_string(file, last_error);
    fputs(",\"last_gesture\":", file); json_string(file, last_gesture);
    fputs(",\"last_action\":", file); json_string(file, last_action);
    fprintf(file, ",\"count\":%u,\"last_result\":%d,\"busy\":%s,\"torch_pid\":%ld,\"updated_ms\":%llu}\n",
            gesture_count, last_result, action_pid > 0 ? "true" : "false", (long)torch_pid, (unsigned long long)monotonic_ms());
    bool good = !ferror(file);
    if (fclose(file) != 0) good = false;
    if (good) (void)rename(temp, path);
}

static void error_message(const char *message)
{
    if (strcmp(last_error, message)) log_event("错误：%s", message);
    snprintf(last_error, sizeof(last_error), "%s", message);
}

static bool bit_set(const unsigned char *bits, unsigned int bit)
{
    return (bits[bit / 8] & (1u << (bit % 8))) != 0;
}

/**
 * @功能：按名称和能力识别独立侧键设备，并通过 EVIOCGRAB 接管。
 * @日期：2026-09-16
 * @参数：无
 * @返回值：成功返回文件描述符，失败返回 -1 并记录原因。
 * @使用说明：如果设备还承载其他按键，拒绝独占，避免影响电源或音量键。
 */
static int acquire_device(void)
{
    DIR *directory = opendir("/dev/input");
    if (!directory) { error_message("无法访问 /dev/input，请检查运行权限"); return -1; }
    struct dirent *entry;
    int found = -1;
    char reason[256] = "未找到 gpio-keys / 735 输入设备";
    while ((entry = readdir(directory)) != NULL) {
        if (strncmp(entry->d_name, "event", 5)) continue;
        char path[300], name[128] = {0};
        snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        unsigned char bits[(KEY_MAX + 8) / 8] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0 || strcmp(name, "gpio-keys") ||
            ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(bits)), bits) < 0 || !bit_set(bits, TARGET_KEY)) {
            close(fd); continue;
        }
        bool extra_keys = false;
        for (unsigned int key = 1; key <= KEY_MAX; key++)
            if (key != TARGET_KEY && bit_set(bits, key)) extra_keys = true;
        if (extra_keys) { snprintf(reason, sizeof(reason), "目标设备包含其他按键，拒绝独占"); close(fd); continue; }
        int clock_id = CLOCK_MONOTONIC;
        if (ioctl(fd, EVIOCSCLOCKID, &clock_id) != 0) {
            snprintf(reason, sizeof(reason), "设置输入时间基准失败：%s", strerror(errno)); close(fd); continue;
        }
        if (ioctl(fd, EVIOCGRAB, 1) != 0) {
            snprintf(reason, sizeof(reason), "按键接管失败：%s", strerror(errno)); close(fd); continue;
        }
        snprintf(device_path, sizeof(device_path), "%.127s", path);
        found = fd;
        break;
    }
    closedir(directory);
    if (found < 0) error_message(reason);
    else { last_error[0] = 0; log_event("已接管 %s，键码 735", device_path); }
    return found;
}

static void on_gesture(GestureKind kind, void *context);

static void reset_gesture(void)
{
    gesture_init(&gesture, config.long_ms, config.double_ms,
                 config.actions[GESTURE_DOUBLE].kind != ACTION_NONE, on_gesture, NULL);
}

static void release_device(void)
{
    if (input_fd >= 0) {
        (void)ioctl(input_fd, EVIOCGRAB, 0);
        close(input_fd);
        input_fd = -1;
        log_event("已释放侧键，恢复系统接收");
    }
    device_path[0] = 0;
    reset_gesture();
}

static void execute_action(const Action *action)
{
    const char *code = NULL;
    switch (action->kind) {
    case ACTION_HOME: code = "3"; break;
    case ACTION_BACK: code = "4"; break;
    case ACTION_RECENTS: code = "187"; break;
    case ACTION_SCREENSHOT: code = "120"; break;
    case ACTION_SCREEN_OFF: code = "223"; break;
    case ACTION_PLAY_PAUSE: code = "85"; break;
    case ACTION_NEXT: code = "87"; break;
    case ACTION_PREVIOUS: code = "88"; break;
    case ACTION_VOLUME_UP: code = "24"; break;
    case ACTION_VOLUME_DOWN: code = "25"; break;
    case ACTION_MUTE: code = "164"; break;
    case ACTION_KEYCODE: code = action->argument; break;
    case ACTION_NOTIFICATIONS:
        execl("/system/bin/cmd", "cmd", "statusbar", "expand-notifications", (char *)NULL); break;
    case ACTION_QUICK_SETTINGS:
        execl("/system/bin/cmd", "cmd", "statusbar", "expand-settings", (char *)NULL); break;
    case ACTION_CAMERA:
        execl("/system/bin/am", "am", "start", "--user", "current", "-a", "android.media.action.STILL_IMAGE_CAMERA", (char *)NULL); break;
    case ACTION_APP_FREEFORM:
    case ACTION_APP: {
        char script[1024]; snprintf(script, sizeof(script), "%s/scripts/app-launch.sh", module_dir);
        execl("/system/bin/sh", "sh", script, action->argument,
              action->kind == ACTION_APP_FREEFORM ? "freeform" : "normal", (char *)NULL); break;
    }
    case ACTION_MIJIA: {
        char script[1024]; snprintf(script, sizeof(script), "%s/scripts/mijia.sh", module_dir);
        execl("/system/bin/sh", "sh", script, "trigger", action->argument, (char *)NULL); break;
    }
    case ACTION_SHELL:
        execl("/system/bin/sh", "sh", "-c", action->argument, (char *)NULL); break;
    case ACTION_TORCH: {
        char message[768];
        int result = torch_toggle(data_dir, message, sizeof(message));
        log_event("手电筒：%s", message);
        _exit(result);
    }
    case ACTION_MENU: _exit(menu_request(data_dir));
    case ACTION_NONE: _exit(0);
    default: _exit(126);
    }
    if (code) execl("/system/bin/input", "input", "keyevent", code, (char *)NULL);
    _exit(127);
}

static void start_action(const Action *action)
{
    if (action->kind == ACTION_NONE) return;
    pid_t child = fork();
    if (child < 0) { error_message("无法创建动作进程"); return; }
    if (child == 0) {
        (void)setpgid(0, 0);
        if (input_fd >= 0) close(input_fd);
        if (lock_fd >= 0) close(lock_fd);
        int null_fd = open("/dev/null", O_RDWR);
        if (null_fd >= 0) {
            for (int fd = 0; fd <= 2; fd++) (void)dup2(null_fd, fd);
            if (null_fd > 2) close(null_fd);
        }
        menu_close_descriptors();
        execute_action(action);
    }
    (void)setpgid(child, child);
    action_pid = child;
    action_started = monotonic_ms();
    action_result_override = 0;
    snprintf(last_action, sizeof(last_action), "%s", action_names[action->kind]);
    last_result = -1;
    log_event("执行动作：%s", last_action);
}

static void poll_action(void)
{
    if (action_pid <= 0) return;
    int status = 0;
    pid_t result = waitpid(action_pid, &status, WNOHANG);
    if (result < 0 && errno == EINTR) return;
    bool timed_out = monotonic_ms() - action_started >= ACTION_TIMEOUT_MS;
    if (result == 0 && !timed_out) return;
    if (result == 0) {
        if (!action_result_override) {
            (void)kill(-action_pid, SIGKILL);
            action_result_override = 124;
            last_result = 124;
            log_event("动作已超时，已发送终止信号：%s", last_action);
            write_status();
        }
        return;
    } else if (result == action_pid) {
        last_result = action_result_override ? action_result_override :
                      (WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status));
        /* 同一动作派生的后台进程不应在命令返回后继续占用资源。 */
        (void)kill(-action_pid, SIGKILL);
    } else { last_result = 125; }
    log_event("动作结束：%s，退出码 %d", last_action, last_result);
    action_pid = 0;
    write_status();
}

static void cancel_actions(void)
{
    queue_size = 0;
    menu_cancel();
    haptic_stop();
    if (action_pid > 0) {
        (void)kill(-action_pid, SIGKILL);
        /* 不阻塞等待子进程；下一次调度回收，退出时由系统接管回收。 */
        if (waitpid(action_pid, NULL, WNOHANG) == action_pid) action_pid = 0;
        action_result_override = 130;
        last_result = 130;
    }
}

static void feedback_poll(void)
{
    int code = haptic_poll(monotonic_ms());
    if (code) log_event("震动反馈失败，vibrator_manager 退出码 %d；动作继续执行", code);
}

static void feedback_trigger(void)
{
    if (!config.haptic) return;
    feedback_poll();
    int code = haptic_trigger(monotonic_ms());
    if (code) log_event("无法创建震动反馈请求，错误 %d；动作继续执行", code);
}

static void on_gesture(GestureKind kind, void *context)
{
    (void)context;
    gesture_count++;
    snprintf(last_gesture, sizeof(last_gesture), "%s", gesture_names[kind]);
    log_event("识别手势：%s", last_gesture);
    Action *action = &config.actions[kind];
    if (action->kind != ACTION_NONE) {
        if (queue_size < QUEUE_CAP) { queue[queue_size++] = *action; feedback_trigger(); }
        else error_message("动作队列已满，本次动作未执行");
    }
    write_status();
}

static bool menu_selected(const Action *action)
{
    if (!action || action->kind == ACTION_MENU || queue_size >= QUEUE_CAP) return false;
    queue[queue_size++] = *action;
    log_event("菜单选择：%s", action_names[action->kind]);
    return true;
}

static bool daemon_running(void)
{
    char path[1024];
    path_for(path, sizeof(path), "daemon.lock");
    int fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    bool running = flock(fd, LOCK_EX | LOCK_NB) != 0 && (errno == EWOULDBLOCK || errno == EAGAIN);
    close(fd);
    return running;
}

static void signal_stop(int signal_number) { (void)signal_number; stopping = 1; }

static bool module_disabled(void)
{
    char path[1024];
    snprintf(path, sizeof(path), "%s/disable", module_dir);
    if (access(path, F_OK) == 0) return true;
    snprintf(path, sizeof(path), "%s/remove", module_dir);
    return access(path, F_OK) == 0 || access(module_dir, F_OK) != 0;
}

static int run_daemon(void)
{
    daemon_context = true;
    char path[1024], error[256];
    path_for(path, sizeof(path), "daemon.lock");
    lock_fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (lock_fd < 0 || flock(lock_fd, LOCK_EX | LOCK_NB) != 0) return 1;
    if (ftruncate(lock_fd, 0) != 0 || dprintf(lock_fd, "%ld\n", (long)getpid()) < 0) return 1;
    struct sigaction handler = {0};
    handler.sa_handler = signal_stop;
    sigemptyset(&handler.sa_mask);
    sigaction(SIGTERM, &handler, NULL);
    sigaction(SIGINT, &handler, NULL);
    if (!config_read(data_dir, &config, error, sizeof(error))) { error_message(error); write_status(); return 1; }
    reset_gesture();
    gesture.callback = on_gesture;
    log_event("监听服务启动，PID %ld", (long)getpid());
    if (!menu_init(data_dir)) log_event("快捷菜单本地接口启动失败");
    uint64_t check_at = 0, discover_at = 0;
    struct stat previous = {0};
    bool ignore_until_release = false, sync_dropped = false;
    while (!stopping) {
        uint64_t now = monotonic_ms();
        if (now >= check_at) {
            check_at = now + 500;
            if (module_disabled()) break;
            path_for(path, sizeof(path), "config.conf");
            struct stat current;
            if (stat(path, &current) == 0 && (current.st_ino != previous.st_ino ||
                current.st_mtim.tv_sec != previous.st_mtim.tv_sec || current.st_mtim.tv_nsec != previous.st_mtim.tv_nsec)) {
                static Config next;
                if (config_read(data_dir, &next, error, sizeof(error))) {
                    cancel_actions();
                    config = next;
                    reset_gesture();
                    gesture.callback = on_gesture;
                    last_error[0] = 0;
                    if (!config.enabled) release_device();
                    /* 配置变化时不把已经按住的键误识别为新动作。 */
                    if (input_fd >= 0) {
                        unsigned char state[(KEY_MAX + 8) / 8] = {0};
                        ignore_until_release = ioctl(input_fd, EVIOCGKEY(sizeof(state)), state) < 0 || bit_set(state, TARGET_KEY);
                    }
                    log_event("配置已应用：%s", config.enabled ? "启用接管" : "暂停接管");
                } else error_message(error);
                previous = current;
            }
            if (config.enabled && input_fd < 0 && now >= discover_at) {
                discover_at = now + 2000;
                input_fd = acquire_device();
                if (input_fd >= 0) {
                    reset_gesture(); gesture.callback = on_gesture;
                    unsigned char state[(KEY_MAX + 8) / 8] = {0};
                    ignore_until_release = ioctl(input_fd, EVIOCGKEY(sizeof(state)), state) < 0 || bit_set(state, TARGET_KEY);
                    sync_dropped = false;
                }
            }
            bool needs_torch = false;
            for (int i = 0; i < 3; i++)
                if (config.actions[i].kind == ACTION_TORCH) needs_torch = true;
            for (uint32_t i = 0; i < config.menu_count; i++)
                if (config.menu[i].action.kind == ACTION_TORCH) needs_torch = true;
            torch_pid = torch_supervise(needs_torch, module_dir, data_dir, now);
            write_status();
        }
        int menu_error = menu_poll(&config, now, menu_selected, torch_pid);
        if (menu_error) log_event("菜单启动失败或超时，代码 %d；请查看菜单组件日志", menu_error);
        poll_action();
        feedback_poll();
        if (!action_pid && queue_size) {
            Action action = queue[0];
            memmove(queue, queue + 1, (--queue_size) * sizeof(Action));
            start_action(&action); write_status();
        }
        uint64_t deadline = gesture_deadline(&gesture);
        int wait_ms = action_pid > 0 || queue_size || haptic_active() ? 100 : 500;
        if (menu_wait_ms() < wait_ms) wait_ms = menu_wait_ms();
        if (check_at <= now) wait_ms = 0;
        else if (check_at - now < (uint64_t)wait_ms) wait_ms = (int)(check_at - now);
        if (input_fd >= 0 && deadline < now + (uint64_t)wait_ms) wait_ms = deadline > now ? (int)(deadline - now) : 0;
        struct pollfd descriptors[2] = {{.fd = input_fd, .events = POLLIN}, {.fd = menu_descriptor(), .events = POLLIN}};
        int ready = poll(descriptors, 2, wait_ms);
        if (ready < 0 && errno != EINTR) { error_message("输入轮询失败"); release_device(); }
        if (ready > 0 && (descriptors[0].revents & (POLLERR | POLLHUP | POLLNVAL))) {
            error_message("输入设备已离线"); release_device();
        } else if (ready > 0 && (descriptors[0].revents & POLLIN)) {
            struct input_event events[32];
            ssize_t count;
            while ((count = read(input_fd, events, sizeof(events))) > 0) {
                if (count % (ssize_t)sizeof(events[0])) { error_message("输入事件长度异常"); release_device(); break; }
                for (size_t i = 0; i < (size_t)count / sizeof(events[0]); i++) {
                    struct input_event *event = &events[i];
                    if (event->type == EV_SYN && event->code == SYN_DROPPED) {
                        reset_gesture(); gesture.callback = on_gesture;
                        ignore_until_release = true; sync_dropped = true;
                        log_event("输入队列丢帧，已丢弃未完成手势");
                    }
                    if (sync_dropped) {
                        if (event->type == EV_SYN && event->code == SYN_REPORT) {
                            unsigned char state[(KEY_MAX + 8) / 8] = {0};
                            ignore_until_release = ioctl(input_fd, EVIOCGKEY(sizeof(state)), state) < 0 || bit_set(state, TARGET_KEY);
                            sync_dropped = false;
                        }
                        continue;
                    }
                    if (event->type != EV_KEY || event->code != TARGET_KEY) continue;
                    if (ignore_until_release) { if (event->value == 0) ignore_until_release = false; continue; }
                    uint64_t at = (uint64_t)event->time.tv_sec * 1000 + (uint64_t)event->time.tv_usec / 1000;
                    gesture_event(&gesture, event->value, at);
                }
            }
            if (count == 0 || (count < 0 && errno != EAGAIN && errno != EINTR)) {
                error_message("输入设备读取失败"); release_device();
            }
        }
        if (input_fd >= 0 && !ignore_until_release) gesture_tick(&gesture, monotonic_ms());
    }
    cancel_actions();
    menu_stop();
    torch_stop(); torch_pid = 0;
    release_device();
    log_event("监听服务已停止");
    write_status();
    close(lock_fd); lock_fd = -1;
    return 0;
}

static void print_state(void)
{
    char error[256];
    if (!config_read(data_dir, &config, error, sizeof(error))) { fprintf(stderr, "%s\n", error); exit(1); }
    fputs("{\"config\":", stdout); config_json(stdout, &config);
    fprintf(stdout, ",\"running\":%s,\"runtime\":", daemon_running() ? "true" : "false");
    char path[1024], buffer[8192] = {0};
    path_for(path, sizeof(path), "status.json");
    FILE *file = fopen(path, "r");
    size_t n = 0;
    if (file) { n = fread(buffer, 1, sizeof(buffer) - 1, file); fclose(file); }
    if (n && buffer[0] == '{') fputs(buffer, stdout); else fputs("{}", stdout);
    path_for(path, sizeof(path), "events.log");
    file = fopen(path, "r");
    buffer[0] = 0;
    if (file) {
        if (fseek(file, 0, SEEK_END) == 0 && ftell(file) > 6000) (void)fseek(file, -6000, SEEK_END);
        else rewind(file);
        n = fread(buffer, 1, sizeof(buffer) - 1, file); buffer[n] = 0;
        fclose(file);
    }
    char *log_start = buffer;
    /* 尾部截取可能落在 UTF-8 多字节字符中，跳过无效的开头。 */
    while (((unsigned char)*log_start & 0xc0) == 0x80) log_start++;
    fputs(",\"logs\":", stdout); json_string(stdout, log_start);
    path_for(path, sizeof(path), "torch.json");
    file = fopen(path, "r"); n = 0;
    if (file) { n = fread(buffer, 1, sizeof(buffer) - 1, file); fclose(file); }
    buffer[n] = 0;
    fputs(",\"torch\":", stdout);
    if (n && buffer[0] == '{') fputs(buffer, stdout); else fputs("{}", stdout);
    path_for(path, sizeof(path), "torch-service.log");
    file = fopen(path, "r"); n = 0;
    if (file) {
        if (fseek(file, 0, SEEK_END) == 0 && ftell(file) > 6000) (void)fseek(file, -6000, SEEK_END);
        else rewind(file);
        n = fread(buffer, 1, sizeof(buffer) - 1, file); fclose(file);
    }
    buffer[n] = 0; log_start = buffer;
    while (((unsigned char)*log_start & 0xc0) == 0x80) log_start++;
    fputs(",\"torch_logs\":", stdout); json_string(stdout, log_start);
    const char *menu_files[] = {"menu-install.log", "menu-launch.log"};
    fputs(",\"menu_logs\":", stdout);
    char menu_logs[8192] = {0}; size_t used = 0;
    for (int i = 0; i < 2; i++) {
        path_for(path, sizeof(path), menu_files[i]); file = fopen(path, "r");
        if (file) {
            used += fread(menu_logs + used, 1, 4000, file); fclose(file);
            menu_logs[used++] = '\n';
        }
    }
    menu_logs[used] = 0; json_string(stdout, menu_logs);
    path_for(path, sizeof(path), "app-launch.log"); file = fopen(path, "r"); n = 0;
    if (file) { n = fread(buffer, 1, sizeof(buffer) - 1, file); fclose(file); }
    buffer[n] = 0;
    fputs(",\"app_logs\":", stdout); json_string(stdout, buffer); fputs("}\n", stdout);
}

static int stop_daemon(void)
{
    if (!daemon_running()) return 0;
    char path[1024];
    path_for(path, sizeof(path), "daemon.lock");
    FILE *file = fopen(path, "r");
    long pid = 0;
    if (!file) return 1;
    int read_count = fscanf(file, "%ld", &pid); fclose(file);
    if (read_count != 1 || pid <= 1) return 1;
    char self[1024] = {0}, other[1024] = {0}, proc[80];
    snprintf(proc, sizeof(proc), "/proc/%ld/exe", pid);
    if (readlink("/proc/self/exe", self, sizeof(self) - 1) < 0 || readlink(proc, other, sizeof(other) - 1) < 0 || strcmp(self, other)) return 1;
    if (kill((pid_t)pid, SIGTERM) != 0) return 1;
    uint64_t until = monotonic_ms() + 3000;
    while (daemon_running() && monotonic_ms() < until) usleep(50000);
    return daemon_running() ? 1 : 0;
}

int main(int argc, char **argv)
{
    umask(0077);
    if (argc == 2 && !strcmp(argv[1], "probe")) {
        int fd = acquire_device();
        if (fd < 0) { fprintf(stderr, "%s\n", last_error); return 1; }
        (void)ioctl(fd, EVIOCGRAB, 0); close(fd);
        printf("{\"device\":\"%s\",\"keycode\":735,\"exclusive_grab\":true}\n", device_path);
        return 0;
    }
    if (argc < 3 || strlen(argv[2]) >= sizeof(data_dir)) return 2;
    strcpy(data_dir, argv[2]);
    if (mkdir(data_dir, 0700) != 0 && errno != EEXIST) { perror("创建数据目录"); return 1; }
    if (!strcmp(argv[1], "init")) {
        char path[1024]; path_for(path, sizeof(path), "config.conf");
        if (access(path, F_OK) == 0) return 0;
        config_defaults(&config);
        return config_write(data_dir, &config) ? 0 : 1;
    }
    if (!strcmp(argv[1], "get")) { print_state(); return 0; }
    if ((!strcmp(argv[1], "save") && argc == 4) || (!strcmp(argv[1], "save-stdin") && argc == 3)) {
        static char text[CONFIG_CAP]; char error[256]; bool good = true;
        if (argc == 4) good = hex_decode(argv[3], text, sizeof(text)) && config_parse(text, &config, error, sizeof(error));
        else good = config_parse_chunks(stdin, &config, error, sizeof(error));
        if (!good) {
            fprintf(stderr, "配置校验失败\n"); return 2;
        }
        if (!config_write(data_dir, &config)) { perror("保存配置"); return 1; }
        print_state(); return 0;
    }
    if (!strcmp(argv[1], "daemon") && argc == 4 && strlen(argv[3]) < sizeof(module_dir)) {
        strcpy(module_dir, argv[3]); return run_daemon();
    }
    if (!strcmp(argv[1], "stop")) return stop_daemon();
    if (!strcmp(argv[1], "test") && argc == 5 && strlen(argv[4]) < sizeof(module_dir)) {
        strcpy(module_dir, argv[4]);
        char error[256];
        if (!config_read(data_dir, &config, error, sizeof(error))) return 1;
        int kind = -1;
        for (int i = 0; i < 3; i++) if (!strcmp(argv[3], gesture_names[i])) kind = i;
        if (kind < 0) return 2;
        if (config.actions[kind].kind != ACTION_NONE) feedback_trigger();
        start_action(&config.actions[kind]);
        while (action_pid && monotonic_ms() - action_started < ACTION_TIMEOUT_MS + 1000) {
            poll_action(); feedback_poll(); usleep(50000);
        }
        if (action_pid > 0) { cancel_actions(); last_result = 124; }
        uint64_t feedback_until = monotonic_ms() + 2500;
        while (haptic_active() && monotonic_ms() < feedback_until) { feedback_poll(); usleep(50000); }
        haptic_stop();
        printf("{\"result\":%d}\n", last_result);
        return last_result == 0 ? 0 : 1;
    }
    fprintf(stderr, "未知命令或参数\n");
    return 2;
}
