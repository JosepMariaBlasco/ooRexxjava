/* WASI compatibility: no child processes in WASI preview 1. */
#ifndef ORX_WASI_SYS_WAIT_H
#define ORX_WASI_SYS_WAIT_H
#include <sys/types.h>
#include <errno.h>
#ifdef __cplusplus
extern "C" {
#endif
#define WNOHANG 1
#define WUNTRACED 2
#define WEXITSTATUS(s) (((s) & 0xff00) >> 8)
#define WTERMSIG(s) ((s) & 0x7f)
#define WIFEXITED(s) (!WTERMSIG(s))
#define WIFSIGNALED(s) (((s) & 0xffff) - 1U < 0xffu)
#define WIFSTOPPED(s) 0
static inline pid_t waitpid(pid_t p, int *st, int o) { (void)p; (void)st; (void)o; errno = ECHILD; return -1; }
static inline pid_t wait(int *st) { (void)st; errno = ECHILD; return -1; }
#ifdef __cplusplus
}
#endif
#endif
