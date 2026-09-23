#ifndef SIDEKEY_MIJIA_MENU_H
#define SIDEKEY_MIJIA_MENU_H
#include "config.h"
void mijia_menu_reset(const MenuItem *items, uint32_t count, const char *session, uint64_t now);
void mijia_menu_disconnect(void);
void mijia_menu_tick(const char *directory, uint64_t now);
int mijia_menu_wait_ms(void);
char mijia_menu_state(uint32_t index);
#endif
