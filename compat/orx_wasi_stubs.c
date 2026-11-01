/* WASI compatibility: functions that cannot exist in WASI preview 1. */
#include <errno.h>
#include <sys/types.h>
pid_t fork(void) { errno = ENOSYS; return -1; }

#include <stdlib.h>
#include <unistd.h>
/* WASI starts every program in "/": start it where the host says it was run (PWD). */
__attribute__((constructor)) static void orx_wasi_initial_cwd(void)
{
    const char *pwd = getenv("PWD");
    if (pwd != NULL && pwd[0] == '/') (void)chdir(pwd);
}

/* no shell in WASI preview 1 */
int system(const char *command) { if (command == NULL) return 0; errno = ENOSYS; return -1; }
