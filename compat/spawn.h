/* WASI compatibility: no processes. Every posix_spawn* fails with ENOSYS. */
#ifndef ORX_WASI_SPAWN_H
#define ORX_WASI_SPAWN_H
#include <sys/types.h>
#include <errno.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct { int dummy; } posix_spawn_file_actions_t;
typedef struct { int dummy; } posix_spawnattr_t;
static inline int posix_spawn_file_actions_init(posix_spawn_file_actions_t *a) { (void)a; return 0; }
static inline int posix_spawn_file_actions_destroy(posix_spawn_file_actions_t *a) { (void)a; return 0; }
static inline int posix_spawn_file_actions_adddup2(posix_spawn_file_actions_t *a, int f, int t) { (void)a; (void)f; (void)t; return 0; }
static inline int posix_spawn_file_actions_addclose(posix_spawn_file_actions_t *a, int f) { (void)a; (void)f; return 0; }
static inline int posix_spawnp(pid_t *p, const char *f, const posix_spawn_file_actions_t *a, const posix_spawnattr_t *t, char *const argv[], char *const envp[]) { (void)p; (void)f; (void)a; (void)t; (void)argv; (void)envp; return ENOSYS; }
static inline int posix_spawn(pid_t *p, const char *f, const posix_spawn_file_actions_t *a, const posix_spawnattr_t *t, char *const argv[], char *const envp[]) { (void)p; (void)f; (void)a; (void)t; (void)argv; (void)envp; return ENOSYS; }
#ifdef __cplusplus
}
#endif
#endif
