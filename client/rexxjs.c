/*
 * rexxjs: runs a Rexx program in a warm JVM (rexxjvm.Server, host/.../Server.java)
 * instead of starting one: what rexxj does, without the JVM's start-up and warm-up.
 *
 *   rexxjs [args...]          as rexxj [args...]
 *
 * The server is found by a socket named after the user and the build
 * ($TMPDIR/.ooRexx-jvm-USER-server-KEY, KEY from rexxj.jar's inode, size and time);
 * if there is none, it is started, detached. The client sends its arguments,
 * environment (with its directory first in PATH, as rexxj does), current directory,
 * pid and ppid, and what its standard streams are; the server opens them itself as
 * /proc/PID/fd/0, 1, 2 (Linux, the same user). SIGINT, SIGTERM and SIGHUP are sent on
 * to the program (a HALT, as natively); the client exits with the program's exit code.
 *
 * Whenever it cannot do that (no server and none can start, a standard stream that is
 * closed or a socket, the API server's daemon), it runs rexxj instead, as if it had not
 * been there. rexxj runs rexxjs when REXXJ_SERVER is set (build.sh's dist/rexxj).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <pwd.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

extern char **environ;

static char dir[PATH_MAX];                  /* where rexxjs, rexxj and rexxj.jar are */
static int sigpipe_fds[2];

/* ---- falling back to rexxj ---- */

static void fallback(char **argv) {
    char rexxj[PATH_MAX + 8];
    snprintf(rexxj, sizeof rexxj, "%s/rexxj", dir);
    unsetenv("REXXJ_SERVER");              /* rexxj must not come back here */
    argv[0] = rexxj;
    execv(rexxj, argv);
    perror("rexxjs: rexxj");
    exit(127);
}

/* ---- the request ---- */

typedef struct { char *p; size_t n, cap; } buf;

static void put(buf *b, const void *d, size_t n) {
    if (b->n + n > b->cap) {
        b->cap = (b->n + n) * 2 + 256;
        b->p = realloc(b->p, b->cap);
        if (!b->p) { perror("rexxjs"); exit(70); }
    }
    memcpy(b->p + b->n, d, n);
    b->n += n;
}

static void put32(buf *b, uint32_t v) {
    unsigned char c[4] = { v >> 24, v >> 16, v >> 8, v };
    put(b, c, 4);
}

static void put64(buf *b, uint64_t v) {
    put32(b, (uint32_t)(v >> 32));
    put32(b, (uint32_t)v);
}

static void putstr(buf *b, const char *s) {
    size_t n = strlen(s);
    put32(b, (uint32_t)n);
    put(b, s, n);
}

static int write_all(int fd, const void *p, size_t n) {
    const char *c = p;
    while (n > 0) {
        ssize_t w = send(fd, c, n, MSG_NOSIGNAL);
        if (w < 0) { if (errno == EINTR) continue; return -1; }
        c += w;
        n -= (size_t)w;
    }
    return 0;
}

/* ---- the server ---- */

static int connect_to(const char *path) {
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path, path, sizeof a.sun_path - 1);
    if (connect(s, (struct sockaddr *)&a, sizeof a) < 0) { close(s); return -1; }
    return s;
}

/* Starts the server, detached, in a session of its own, its output to SOCKET.log. */
static void start_server(const char *path) {
    pid_t p = fork();
    if (p < 0) return;
    if (p > 0) return;
    setsid();
    if (fork() > 0) _exit(0);              /* not a child of the client: no zombie */
    char log[PATH_MAX + 8];
    snprintf(log, sizeof log, "%s.log", path);
    int null = open("/dev/null", O_RDONLY);
    int out = open(log, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (null >= 0) dup2(null, 0);
    if (out >= 0) { dup2(out, 1); dup2(out, 2); }
    if (chdir("/") < 0) { /* stay where we are */ }

    char *av[64];
    int n = 0;
    static char home[PATH_MAX + 32], exe[PATH_MAX + 32], agent[PATH_MAX + 32], cp[2 * PATH_MAX + 32];
    snprintf(exe, sizeof exe, "-Drexxjvm.executable=%s/rexx", dir);
    snprintf(home, sizeof home, "-Drexxjvm.home=%s", dir);
    snprintf(agent, sizeof agent, "-javaagent:%s/rexxj.jar", dir);
    snprintf(cp, sizeof cp, "%s/rexxj.jar:%s/bsf4oorexx.jar", dir, dir);
    av[n++] = "java";
    av[n++] = "-Xss16m";
    char *opts = getenv("REXXJ_JAVA_OPTIONS");     /* as rexxj: split at blanks */
    if (opts) {
        opts = strdup(opts);
        for (char *t = strtok(opts, " \t"); t && n < 50; t = strtok(NULL, " \t")) av[n++] = t;
    }
    av[n++] = exe;
    av[n++] = home;
    av[n++] = agent;
    av[n++] = "-cp";
    av[n++] = cp;
    av[n++] = "rexxjvm.Server";
    av[n++] = (char *)path;
    av[n] = NULL;
    execvp("java", av);
    _exit(127);
}

/* ---- signals: sent on to the program ---- */

static void on_signal(int sig) {
    unsigned char c = (unsigned char)sig;
    int e = errno;
    (void)!write(sigpipe_fds[1], &c, 1);
    errno = e;
}

static void forward(int sig) {
    struct sigaction old;
    if (sigaction(sig, NULL, &old) == 0 && old.sa_handler == SIG_IGN) return;   /* left ignored, as natively */
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART;
    sigaction(sig, &sa, NULL);
}

/* ---- main ---- */

int main(int argc, char **argv) {
    (void)argc;
    char self[PATH_MAX];
    ssize_t len = readlink("/proc/self/exe", self, sizeof self - 1);
    if (len <= 0) { perror("rexxjs: /proc/self/exe"); return 70; }
    self[len] = 0;
    strcpy(dir, self);
    char *slash = strrchr(dir, '/');
    if (slash) *slash = 0;

    /* what only rexxj does: the API server's daemon */
    if (argv[1] && strcmp(argv[1], "--orx-wasm-rxapi") == 0) fallback(argv);

    /* the standard streams: kinds and offsets; closed or a socket: rexxj */
    char kind[3];
    int64_t offset[3];
    for (int fd = 0; fd < 3; fd++) {
        struct stat st;
        if (fstat(fd, &st) < 0 || S_ISSOCK(st.st_mode)) fallback(argv);
        offset[fd] = -1;
        if (isatty(fd)) kind[fd] = 'T';
        else if (S_ISREG(st.st_mode)) {
            kind[fd] = 'F';
            offset[fd] = lseek(fd, 0, SEEK_CUR);
        } else kind[fd] = 'P';
    }

    /* the socket: this user, this build */
    char jar[PATH_MAX + 16];
    snprintf(jar, sizeof jar, "%s/rexxj.jar", dir);
    struct stat js;
    if (stat(jar, &js) < 0) fallback(argv);
    const char *tmp = getenv("TMPDIR");
    if (!tmp || !*tmp) tmp = "/tmp";
    struct passwd *pw = getpwuid(getuid());
    char user[64];
    snprintf(user, sizeof user, "%s", pw ? pw->pw_name : "user");
    for (char *c = user; *c; c++)
        if (!((*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || (*c >= '0' && *c <= '9') || *c == '.' || *c == '_' || *c == '-')) *c = '_';
    char path[PATH_MAX];
    snprintf(path, sizeof path, "%s/.ooRexx-jvm-%s-server-%llx%llx%llx", tmp, user,
             (unsigned long long)js.st_ino, (unsigned long long)js.st_size,
             (unsigned long long)js.st_mtime);
    if (strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) fallback(argv);

    int s = connect_to(path);
    if (s < 0) {
        start_server(path);
        struct timespec ts = { 0, 50 * 1000 * 1000 };
        for (int i = 0; i < 600 && s < 0; i++) {       /* up to 30 s */
            nanosleep(&ts, NULL);
            s = connect_to(path);
        }
        if (s < 0) fallback(argv);
    }

    /* the request */
    buf b = { 0 };
    put(&b, "RXJ1", 4);
    put32(&b, (uint32_t)getpid());
    put32(&b, (uint32_t)getppid());
    char cwd[PATH_MAX];
    putstr(&b, getcwd(cwd, sizeof cwd) ? cwd : "/");
    int nargs = 0;
    for (char **a = argv + 1; *a; a++) nargs++;
    put32(&b, (uint32_t)nargs);
    for (char **a = argv + 1; *a; a++) putstr(&b, *a);
    /* the environment, with this directory first in PATH, as rexxj puts it */
    const char *oldpath = getenv("PATH");
    char *newpath = malloc(strlen(dir) + (oldpath ? strlen(oldpath) : 0) + 8);
    sprintf(newpath, "PATH=%s%s%s", dir, oldpath ? ":" : "", oldpath ? oldpath : "");
    int nenv = 1;
    for (char **e = environ; *e; e++) if (strncmp(*e, "PATH=", 5) != 0) nenv++;
    put32(&b, (uint32_t)nenv);
    putstr(&b, newpath);
    for (char **e = environ; *e; e++) if (strncmp(*e, "PATH=", 5) != 0) putstr(&b, *e);
    for (int fd = 0; fd < 3; fd++) {
        put(&b, &kind[fd], 1);
        put64(&b, (uint64_t)offset[fd]);
    }

    if (pipe2(sigpipe_fds, O_CLOEXEC) < 0) { perror("rexxjs"); return 70; }
    forward(SIGINT);
    forward(SIGTERM);
    forward(SIGHUP);
    if (write_all(s, b.p, b.n) < 0) {
        fprintf(stderr, "rexxjs: the server went away\n");
        return 70;
    }

    /* wait for the answer, sending signals on */
    unsigned char ans[13];
    size_t got = 0;
    for (;;) {
        struct pollfd p[2] = { { s, POLLIN, 0 }, { sigpipe_fds[0], POLLIN, 0 } };
        if (poll(p, 2, -1) < 0) {
            if (errno == EINTR) continue;
            perror("rexxjs: poll");
            return 70;
        }
        if (p[1].revents & POLLIN) {
            unsigned char sig;
            if (read(sigpipe_fds[0], &sig, 1) == 1) {
                unsigned char m[5] = { 'S', 0, 0, 0, sig };
                write_all(s, m, 5);
            }
        }
        if (p[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t r = read(s, ans + got, sizeof ans - got);
            if (r < 0 && errno == EINTR) continue;
            if (r <= 0) {
                fprintf(stderr, "rexxjs: the server went away\n");
                return 70;
            }
            got += (size_t)r;
            if (got == sizeof ans) break;
        }
    }
    if (ans[0] != 'X') {
        fprintf(stderr, "rexxjs: unexpected answer from the server\n");
        return 70;
    }
    int rc = (int)(((uint32_t)ans[1] << 24) | ((uint32_t)ans[2] << 16) | ((uint32_t)ans[3] << 8) | ans[4]);
    int64_t in_at = 0;
    for (int i = 5; i < 13; i++) in_at = (in_at << 8) | ans[i];

    /* our offsets where the program left them: output was appended, input read so far */
    if (kind[1] == 'F') lseek(1, 0, SEEK_END);
    if (kind[2] == 'F') lseek(2, 0, SEEK_END);
    if (kind[0] == 'F' && in_at >= 0) lseek(0, in_at, SEEK_SET);
    return rc & 0xff;
}
