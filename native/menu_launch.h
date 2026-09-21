#ifndef SIDEKEY_MENU_LAUNCH_H
#define SIDEKEY_MENU_LAUNCH_H
#include <stdbool.h>
#include <stdint.h>
typedef struct {
    uint64_t deadline;
    bool command_done, ui_ready;
    int error;
} MenuLaunch;
/* -1 表示继续调度；0 仅在命令完成且组件已握手时返回。 */
int menu_launch_result(const MenuLaunch *state, uint64_t now);
#endif
