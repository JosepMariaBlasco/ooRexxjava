/* WASI compatibility, included in every translation unit (-include). */
#ifndef ORX_WASI_COMPAT_H
#define ORX_WASI_COMPAT_H
#include <sys/types.h>
#ifdef __cplusplus
extern "C" {
#endif
static inline uid_t getuid(void) { return 0; }
static inline uid_t geteuid(void) { return 0; }
static inline gid_t getgid(void) { return 0; }
static inline gid_t getegid(void) { return 0; }
/* no process tree, no priorities, no fork in WASI preview 1 */
static inline pid_t getppid(void) { return 1; }
static inline pid_t getpgid(pid_t p) { (void)p; return 1; }
#define PRIO_PROCESS 0
#define PRIO_PGRP 1
#define PRIO_USER 2
static inline int getpriority(int w, unsigned int who) { (void)w; (void)who; return 0; }
static inline int setpriority(int w, unsigned int who, int p) { (void)w; (void)who; (void)p; return -1; }
pid_t fork(void);
#ifdef __cplusplus
}
#endif
#ifdef __cplusplus
/* libc++ declares std::abs(long) and std::exception_ptr only in these */
#include <cstdlib>
#include <cmath>
#include <exception>
#endif
#endif
