/* WASI compatibility: no System V semaphores; every call fails with ENOSYS. */
#ifndef ORX_WASI_SYS_SEM_H
#define ORX_WASI_SYS_SEM_H
#include <sys/ipc.h>
#include <errno.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
#define GETVAL 12
#define SETVAL 16
#define GETPID 11
#define GETNCNT 14
#define GETZCNT 15
#define SETALL 17
#define GETALL 13
#define SEM_UNDO 0x1000
struct sembuf { unsigned short sem_num; short sem_op; short sem_flg; };
static inline int semget(key_t k, int n, int f) { (void)k; (void)n; (void)f; errno = ENOSYS; return -1; }
static inline int semop(int id, struct sembuf *s, size_t n) { (void)id; (void)s; (void)n; errno = ENOSYS; return -1; }
static inline int semctl(int id, int n, int cmd, ...) { (void)id; (void)n; (void)cmd; errno = ENOSYS; return -1; }
#ifdef __cplusplus
}
#endif
#endif
