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

/* umask: remembered only, WASI applies no permission bits at creation */
#include <sys/stat.h>
static mode_t orx_wasi_umask = 022;
mode_t umask(mode_t m) { mode_t old = orx_wasi_umask; orx_wasi_umask = m & 0777; return old; }

/* no time zones in WASI preview 1: localtime() is UTC */
char *tzname[2] = { (char *)"UTC", (char *)"UTC" };
void tzset(void) {}

/* getservbyname/getservbyport (netdb.h here) from /etc/services: lines of
   "name port/proto aliases... # comment"; the port in network byte order */
#include <stdio.h>
#include <string.h>
#include <netdb.h>
static struct servent *orx_wasi_service(const char *name, int port, const char *proto)
{
    static struct servent se;
    static char line[512], *aliases[16];
    FILE *f = fopen("/etc/services", "r");
    if (f == NULL) return NULL;
    while (fgets(line, sizeof(line), f) != NULL)
    {
        char *hash = strchr(line, '#');
        if (hash != NULL) *hash = '\0';
        char *save = NULL;
        char *n = strtok_r(line, " \t\r\n", &save);
        char *pp = strtok_r(NULL, " \t\r\n", &save);
        if (n == NULL || pp == NULL) continue;
        char *slash = strchr(pp, '/');
        if (slash == NULL) continue;
        *slash = '\0';
        int p = atoi(pp);
        char *pr = slash + 1;
        if (proto != NULL && strcmp(proto, pr) != 0) continue;
        int na = 0, match = name == NULL ? p == port : strcmp(n, name) == 0;
        char *a;
        while ((a = strtok_r(NULL, " \t\r\n", &save)) != NULL && na < 15)
        {
            aliases[na++] = a;
            if (name != NULL && strcmp(a, name) == 0) match = 1;
        }
        aliases[na] = NULL;
        if (!match) continue;
        fclose(f);
        se.s_name = n; se.s_aliases = aliases; se.s_proto = pr;
        se.s_port = (int)(((p & 0xff) << 8) | ((p >> 8) & 0xff));   /* htons */
        return &se;
    }
    fclose(f);
    return NULL;
}
struct servent *getservbyname(const char *name, const char *proto) { return name == NULL ? NULL : orx_wasi_service(name, 0, proto); }
struct servent *getservbyport(int port, const char *proto)
{
    return orx_wasi_service(NULL, ((port & 0xff) << 8) | ((port >> 8) & 0xff), proto);
}
