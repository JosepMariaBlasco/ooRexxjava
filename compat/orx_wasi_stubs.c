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

/* Local time: wasi-libc has no time zones (its localtime() is gmtime()), so the
   zone comes from the launcher (host/.../TimeZones.java), which reads TZ as the
   C library does natively, the JVM's default zone when it is unset. These replace
   wasi-libc's localtime, localtime_r, mktime, tzset and tzname: TIME(), DATE(),
   the streams' and RexxUtil's file times, everything that asks for local time. */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#define RXJ_IMPORT(name) __attribute__((import_module("rexxjvm"), import_name(#name)))
RXJ_IMPORT(tz_offset)
int64_t rxj_tz_offset(int64_t seconds, const char *tz);
RXJ_IMPORT(tz_name)
int rxj_tz_name(const char *tz, int daylight, char *buf, int size);

static char orx_tz_names[2][64] = { "UTC", "UTC" };
char *tzname[2] = { orx_tz_names[0], orx_tz_names[1] };

void tzset(void)
{
    const char *tz = getenv("TZ");
    for (int i = 0; i < 2; i++)
        if (!rxj_tz_name(tz, i, orx_tz_names[i], sizeof(orx_tz_names[i])))
            strcpy(orx_tz_names[i], "UTC");
}

/* the offset east of UTC at t, in seconds, and whether DST is in effect then */
static long orx_tz_at(time_t t, int *dst)
{
    int64_t r = rxj_tz_offset((int64_t)t, getenv("TZ"));
    *dst = (int)(r & 1);
    return (long)(r >> 1);   /* r = offset * 2 + dst, the offset may be negative */
}

struct tm *localtime_r(const time_t *t, struct tm *tm)
{
    int dst;
    time_t local = *t + orx_tz_at(*t, &dst);
    if (gmtime_r(&local, tm) == NULL) return NULL;
    tm->tm_isdst = dst;
    return tm;
}

struct tm *localtime(const time_t *t)
{
    static _Thread_local struct tm tm;
    return localtime_r(t, &tm);
}

/* days from 1970-01-01 to y-m-d of the proleptic Gregorian calendar (m 1..12) */
static int64_t orx_days(int64_t y, int64_t m, int64_t d)
{
    y -= m <= 2;
    int64_t era = (y >= 0 ? y : y - 399) / 400;
    int64_t yoe = y - era * 400;
    int64_t doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    int64_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe - 719468;
}

/* the fields as if they were UTC, normalized as mktime() does (any may be out of range) */
static int64_t orx_fields_seconds(const struct tm *tm)
{
    int64_t mon = tm->tm_mon, y = (int64_t)tm->tm_year + 1900 + mon / 12;
    mon %= 12;
    if (mon < 0) { mon += 12; y--; }
    return orx_days(y, mon + 1, 1) * 86400 + ((int64_t)tm->tm_mday - 1) * 86400
         + (int64_t)tm->tm_hour * 3600 + (int64_t)tm->tm_min * 60 + tm->tm_sec;
}

time_t mktime(struct tm *tm)
{
    int64_t local = orx_fields_seconds(tm);
    /* the offsets a day before and after: the two candidates around a transition */
    int d0, d1, dst;
    long o0 = orx_tz_at((time_t)(local - 86400), &d0);
    long o1 = orx_tz_at((time_t)(local + 86400), &d1);
    time_t t0 = (time_t)(local - o0), t1 = (time_t)(local - o1), t;
    int ok0 = orx_tz_at(t0, &dst) == o0, ok1 = orx_tz_at(t1, &dst) == o1;
    if (ok0 && ok1 && t0 != t1)       /* repeated hour: tm_isdst chooses, else standard time, as glibc */
        t = tm->tm_isdst > 0 ? (d0 ? t0 : t1) : (d0 ? t1 : t0);
    else if (ok0 || ok1)
        t = ok0 ? t0 : t1;
    else                              /* skipped hour: as glibc, the time after the gap */
        t = t0;
    if (localtime_r(&t, tm) == NULL) return (time_t)-1;
    return t;
}

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
