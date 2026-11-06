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
/* the real process ids, from the host (orx_wasi_host.c): the shared Rexx API
   server keys sessions by process id */
pid_t getppid(void);
/* no process tree, no priorities, no fork in WASI preview 1 */
static inline pid_t getpgid(pid_t p) { (void)p; return 1; }
#define PRIO_PROCESS 0
#define PRIO_PGRP 1
#define PRIO_USER 2
static inline int getpriority(int w, unsigned int who) { (void)w; (void)who; return 0; }
static inline int setpriority(int w, unsigned int who, int p) { (void)w; (void)who; (void)p; return -1; }
pid_t fork(void);
/* no sessions, users or groups to change, signals to send, owners or roots
   in WASI preview 1 (rxunixsys calls them): they fail as unsupported */
#include <errno.h>
static inline pid_t getpgrp(void) { return 1; }
static inline pid_t getsid(pid_t p) { (void)p; return 1; }
static inline pid_t setsid(void) { errno = ENOSYS; return -1; }
static inline int setpgid(pid_t p, pid_t g) { (void)p; (void)g; errno = ENOSYS; return -1; }
static inline pid_t setpgrp(void) { errno = ENOSYS; return -1; }
static inline int setuid(uid_t u) { (void)u; errno = ENOSYS; return -1; }
static inline int seteuid(uid_t u) { (void)u; errno = ENOSYS; return -1; }
static inline int setgid(gid_t g) { (void)g; errno = ENOSYS; return -1; }
static inline int setegid(gid_t g) { (void)g; errno = ENOSYS; return -1; }
static inline int setgroups(size_t n, const gid_t *l) { (void)n; (void)l; errno = ENOSYS; return -1; }
static inline int kill(pid_t p, int s) { (void)p; (void)s; errno = ENOSYS; return -1; }
static inline int chown(const char *f, uid_t u, gid_t g) { (void)f; (void)u; (void)g; errno = ENOSYS; return -1; }
static inline int lchown(const char *f, uid_t u, gid_t g) { (void)f; (void)u; (void)g; errno = ENOSYS; return -1; }
static inline int chroot(const char *d) { (void)d; errno = ENOSYS; return -1; }
/* orx_wasi_stubs.c: umask only remembered (no permission bits are applied at
   creation); tzset and tzname over the launcher's time zone, as localtime and
   mktime (TZ, else the JVM's default zone) */
mode_t umask(mode_t m);
void tzset(void);
extern char *tzname[2];
#ifdef __cplusplus
}
#endif

/* File system calls done by the launcher instead of WASI (orx_wasi_fs.c says
   why). The system headers come first, so that their declarations keep their
   names and only the calls after this point are redirected; their include
   guards keep later inclusions from declaring them again. */
#include <stdio.h>
#include <unistd.h>
#include <utime.h>
#include <sys/stat.h>
#include <sys/time.h>
#ifdef __cplusplus
extern "C" {
#endif
int orx_wasi_stat(const char *__restrict, struct stat *__restrict);
int orx_wasi_lstat(const char *__restrict, struct stat *__restrict);
int orx_wasi_fstat(int, struct stat *);
int orx_wasi_access(const char *, int);
int orx_wasi_chmod(const char *, mode_t);
int orx_wasi_rename(const char *, const char *);
int orx_wasi_utimes(const char *, const struct timeval [2]);
int orx_wasi_utime(const char *, const struct utimbuf *);
int orx_wasi_chdir(const char *);
int orx_wasi_symlink(const char *, const char *);
#ifdef __cplusplus
}
#endif
/* function-like: "struct stat" is left alone; stat64, lstat64 and fstat64 are
   this libc's macros for stat, lstat and fstat (_GNU_SOURCE), so they are redirected too */
#define stat(p, b)    orx_wasi_stat(p, b)
#define lstat(p, b)   orx_wasi_lstat(p, b)
#define fstat(f, b)   orx_wasi_fstat(f, b)
#define access(p, m)  orx_wasi_access(p, m)
#define chmod(p, m)   orx_wasi_chmod(p, m)
#define rename(f, t)  orx_wasi_rename(f, t)
#define utimes(p, t)  orx_wasi_utimes(p, t)
#define utime(p, t)   orx_wasi_utime(p, t)
#define chdir(p)      orx_wasi_chdir(p)
#define symlink(t, l) orx_wasi_symlink(t, l)
#ifdef __cplusplus
/* libc++ declares std::abs(long) and std::exception_ptr only in these */
#include <cstdlib>
#include <cmath>
#include <exception>
#endif
#endif
