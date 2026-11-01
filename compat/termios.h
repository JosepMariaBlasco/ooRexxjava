/* WASI compatibility: no terminal control. tcgetattr/tcsetattr fail with ENOTTY. */
#ifndef ORX_WASI_TERMIOS_H
#define ORX_WASI_TERMIOS_H
#include <errno.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef unsigned int tcflag_t; typedef unsigned char cc_t; typedef unsigned int speed_t;
#define NCCS 32
struct termios { tcflag_t c_iflag, c_oflag, c_cflag, c_lflag; cc_t c_cc[NCCS]; };
#define ECHO 0000010
#define ICANON 0000002
#define ISIG 0000001
#define VMIN 6
#define VTIME 5
#define TCSANOW 0
#define TCSADRAIN 1
#define TCSAFLUSH 2
static inline int tcgetattr(int fd, struct termios *t) { (void)fd; (void)t; errno = ENOTTY; return -1; }
static inline int tcsetattr(int fd, int a, const struct termios *t) { (void)fd; (void)a; (void)t; errno = ENOTTY; return -1; }
#ifdef __cplusplus
}
#endif
#endif
