#include "menu_launch.h"
int menu_launch_result(const MenuLaunch *state, uint64_t now)
{
    if (state->error) return state->error;
    if (now >= state->deadline) return 124;
    return state->command_done && state->ui_ready ? 0 : -1;
}
