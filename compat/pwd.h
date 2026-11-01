/* WASI compatibility: wasi-libc has no passwd database. One user: the process's, from the environment. */
#ifndef ORX_WASI_PWD_H
#define ORX_WASI_PWD_H
#include <sys/types.h>
#include <stdlib.h>
#include <string.h>
#ifdef __cplusplus
extern "C" {
#endif
struct passwd { char *pw_name; char *pw_passwd; uid_t pw_uid; gid_t pw_gid; char *pw_gecos; char *pw_dir; char *pw_shell; };
static inline struct passwd *orx_wasi_user(void)
{
    static struct passwd p;
    const char *u = getenv("USER"); const char *h = getenv("HOME");
    p.pw_name = (char *)(u != NULL ? u : "wasi");
    p.pw_passwd = (char *)"x"; p.pw_uid = 0; p.pw_gid = 0; p.pw_gecos = p.pw_name;
    p.pw_dir = (char *)(h != NULL ? h : "/"); p.pw_shell = (char *)"/bin/sh";
    return &p;
}
static inline struct passwd *getpwuid(uid_t uid) { (void)uid; return orx_wasi_user(); }
static inline struct passwd *getpwnam(const char *name) { struct passwd *p = orx_wasi_user(); return (name != NULL && strcmp(name, p->pw_name) == 0) ? p : NULL; }
#ifdef __cplusplus
}
#endif
#endif
