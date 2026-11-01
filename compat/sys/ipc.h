/* WASI compatibility: no System V IPC. */
#ifndef ORX_WASI_SYS_IPC_H
#define ORX_WASI_SYS_IPC_H
#include <sys/types.h>
typedef int key_t;
#define IPC_CREAT 01000
#define IPC_EXCL 02000
#define IPC_NOWAIT 04000
#define IPC_PRIVATE ((key_t)0)
#define IPC_RMID 0
#define IPC_SET 1
#define IPC_STAT 2
#endif
