/* WASI compatibility: wasi-libc has no group database. One group: the
   process's (gid 0, as getgid() in orx_wasi_compat.h), with no members. */
#ifndef ORX_WASI_GRP_H
#define ORX_WASI_GRP_H
#include <sys/types.h>
#include <string.h>
#ifdef __cplusplus
extern "C" {
#endif
struct group { char *gr_name; char *gr_passwd; gid_t gr_gid; char **gr_mem; };
static inline struct group *orx_wasi_group(void)
{
    static char *none[] = { 0 };
    static struct group g;
    g.gr_name = (char *)"wasi"; g.gr_passwd = (char *)"x"; g.gr_gid = 0; g.gr_mem = none;
    return &g;
}
static inline struct group *getgrgid(gid_t gid) { return gid == 0 ? orx_wasi_group() : 0; }
static inline struct group *getgrnam(const char *name) { struct group *g = orx_wasi_group(); return (name != 0 && strcmp(name, g->gr_name) == 0) ? g : 0; }
#ifdef __cplusplus
}
#endif
#endif
