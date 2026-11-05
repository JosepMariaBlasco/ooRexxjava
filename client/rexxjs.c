/*
 * rexxjs: runs a Rexx program in a warm JVM (rexxjvm.Server, host/.../Server.java)
 * instead of starting one: what rexxj does, without the JVM's start-up and warm-up.
 *
 *   rexxjs [args...]          as rexxj [args...]
 *   rexxjs --orx-server-stop  ends this build's server, and the programs it runs
 *
 * The server is found by a socket named after the user and the build
 * ($TMPDIR/.ooRexx-jvm-USER-server-KEY, KEY from rexxj.jar's inode, size and time);
 * if there is none, it is started, detached. The client sends its arguments,
 * environment (with its directory first in PATH, as rexxj does), current directory,
 * pid and ppid, and what its standard streams are. On Linux the server opens them
 * itself as /proc/PID/fd/0, 1, 2 (the same user may); elsewhere, or on Linux when
 * REXXJ_SERVER_RELAY is set, they are relayed through the socket, and the client runs
 * the program's commands itself, with its own streams (relay(); Relay.java). SIGINT,
 * SIGTERM and SIGHUP are sent on to the program (a HALT, as natively); the client exits
 * with the program's exit code.
 *
 * Whenever it cannot do that (no server and none can start, a standard stream that is
 * closed, or a socket when not relayed, the API server's daemon), it runs rexxj instead,
 * as if it had not been there. rexxj runs rexxjs when REXXJ_SERVER is set (build.sh's
 * dist/rexxj).
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
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#ifdef __APPLE__
#include <mach-o/dyld.h>
#endif

extern char **environ;

/* pipe2, SOCK_CLOEXEC and MSG_NOSIGNAL are not everywhere (macOS: SO_NOSIGPIPE) */
#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif
static int cloexec(int fd) { return fd < 0 ? fd : (fcntl(fd, F_SETFD, FD_CLOEXEC), fd); }

static int pipe_cloexec(int p[2]) {
    if (pipe(p) < 0) return -1;
    cloexec(p[0]);
    cloexec(p[1]);
    return 0;
}

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
    int s = cloexec(socket(AF_UNIX, SOCK_STREAM, 0));
    if (s < 0) return -1;
#ifdef SO_NOSIGPIPE
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
#endif
    struct sockaddr_un a;
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path, path, sizeof a.sun_path - 1);
    if (connect(s, (struct sockaddr *)&a, sizeof a) < 0) { close(s); return -1; }
    return s;
}

/* Starts the server, detached, in a session of its own, its output to SOCKET.log.
   Returns the read end of a pipe whose write end the server holds for as long as it
   lives: end of file there means it has gone (it cannot run: Java before 16, a bad
   option...), so the client stops waiting for its socket. -1 if there is none. */
static int start_server(const char *path) {
    int alive[2];
    if (pipe_cloexec(alive) < 0) alive[0] = alive[1] = -1;
    pid_t p = fork();
    if (p < 0) {
        if (alive[0] >= 0) { close(alive[0]); close(alive[1]); }
        return -1;
    }
    if (p > 0) {
        if (alive[1] >= 0) close(alive[1]);
        return alive[0];
    }
    setsid();
    if (fork() > 0) _exit(0);              /* not a child of the client: no zombie */
    if (alive[0] >= 0) close(alive[0]);
    if (alive[1] >= 0) fcntl(alive[1], F_SETFD, 0);     /* kept by the server, through exec */
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
    /* rexxj.jar alone: each run has a class loader of its own for BSF4ooRexx and the
       client's CLASSPATH (rexxjvm.Server) */
    snprintf(cp, sizeof cp, "%s/rexxj.jar", dir);
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

static void on_signal(int sig) {   /* SIGCHLD too, relayed: a command ended */
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

/* ---- relayed streams (Relay.java has the frames) ---- */

/* the server's frames come through a buffer: a read for many of them, not three for each */
static int relay_sock = -1;
static unsigned char sbuf[128 << 10];
static size_t sb_at, sb_n;

static int read_full(int fd, void *p, size_t n) {
    char *c = p;
    if (fd == relay_sock) {
        while (n > 0) {
            if (sb_at == sb_n) {
                ssize_t r = read(fd, sbuf, sizeof sbuf);
                if (r < 0 && errno == EINTR) continue;
                if (r <= 0) return -1;
                sb_at = 0;
                sb_n = (size_t)r;
            }
            size_t k = sb_n - sb_at < n ? sb_n - sb_at : n;
            memcpy(c, sbuf + sb_at, k);
            sb_at += k;
            c += k;
            n -= k;
        }
        return 0;
    }
    while (n > 0) {
        ssize_t r = read(fd, c, n);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return -1;
        c += r;
        n -= (size_t)r;
    }
    return 0;
}

static int out_all(int fd, const void *p, size_t n) {   /* to our own output, not the socket */
    const char *c = p;
    while (n > 0) {
        ssize_t w = write(fd, c, n);
        if (w < 0) { if (errno == EINTR) continue; return -1; }
        c += w;
        n -= (size_t)w;
    }
    return 0;
}

static uint32_t get32(const unsigned char *c) {
    return ((uint32_t)c[0] << 24) | ((uint32_t)c[1] << 16) | ((uint32_t)c[2] << 8) | c[3];
}

static int read32(int s, int32_t *v) {
    unsigned char c[4];
    if (read_full(s, c, 4) < 0) return -1;
    *v = (int32_t)get32(c);
    return 0;
}

static char *readstr(int s) {
    int32_t n;
    if (read32(s, &n) < 0 || n < 0 || n > (64 << 20)) return NULL;
    char *p = malloc((size_t)n + 1);
    if (!p || read_full(s, p, (size_t)n) < 0) { free(p); return NULL; }
    p[n] = 0;
    return p;
}

#define CAPTURE_OUT 1
#define CAPTURE_ERR 2
#define CAPTURE_SAME 4
#define GIVE_INPUT 8

/* A command the server asked us to run: its pipes for what the program captures. */
typedef struct command {
    int32_t id;
    pid_t pid;
    int out, err, in;                   /* our ends; -1 when closed or not captured */
    char *input; size_t inlen, inat;    /* what goes to its standard input */
    int status, reaped;
    struct command *next;
} command;

static command *commands;
static struct sigaction pipe_was;       /* SIGPIPE as we got it, for the commands */

static int nonblock(int fd) { return fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK); }

static void close_fd(int *fd) { if (*fd >= 0) { close(*fd); *fd = -1; } }

/* A broken pipe on our own output: what natively would have ended the program. */
static void broken(void) {
    if (pipe_was.sa_handler == SIG_IGN) return;          /* natively an error; the output is lost */
    sigaction(SIGPIPE, &pipe_was, NULL);
    raise(SIGPIPE);
}

static int frame(int s, unsigned char type, int32_t id, const void *p, int32_t n) {
    unsigned char h[9] = { type };
    size_t hn = 1;
    if (type == 'o' || type == 'e' || type == 'D') {
        h[1] = id >> 24; h[2] = id >> 16; h[3] = id >> 8; h[4] = id;
        hn = 5;
    }
    h[hn] = n >> 24; h[hn + 1] = n >> 16; h[hn + 2] = n >> 8; h[hn + 3] = n;
    hn += 4;
    if (write_all(s, h, hn) < 0) return -1;
    return p && n > 0 ? write_all(s, p, (size_t)n) : 0;
}

/* 'C': starts the command, with our standard streams but for those captured */
static int start_command(int s) {
    int32_t id, argc, envc, flags, inlen;
    if (read32(s, &id) < 0 || read32(s, &argc) < 0 || argc < 1 || argc > 1 << 20) return -1;
    char **av = calloc((size_t)argc + 1, sizeof *av);
    for (int i = 0; i < argc; i++) if (!(av[i] = readstr(s))) return -1;
    if (read32(s, &envc) < 0 || envc < 0 || envc > 1 << 20) return -1;
    char **ev = calloc((size_t)envc + 1, sizeof *ev);
    for (int i = 0; i < envc; i++) if (!(ev[i] = readstr(s))) return -1;
    char *cwd = readstr(s);
    if (!cwd || read32(s, &flags) < 0 || read32(s, &inlen) < 0 || inlen < 0) return -1;
    char *input = malloc((size_t)inlen + 1);
    if (!input || read_full(s, input, (size_t)inlen) < 0) return -1;

    command *c = calloc(1, sizeof *c);
    c->id = id;
    c->out = c->err = c->in = -1;
    c->input = input;
    c->inlen = (size_t)inlen;
    int po[2] = { -1, -1 }, pe[2] = { -1, -1 }, pi[2] = { -1, -1 }, ex[2] = { -1, -1 };
    int ok = pipe_cloexec(ex) == 0
          && (!(flags & CAPTURE_OUT) || pipe_cloexec(po) == 0)
          && (!(flags & CAPTURE_ERR) || pipe_cloexec(pe) == 0)
          && (!(flags & GIVE_INPUT) || pipe_cloexec(pi) == 0);
    pid_t pid = ok ? fork() : -1;
    if (pid == 0) {
        /* the dispositions the command would have had natively: ours, as we got them */
        sigaction(SIGPIPE, &pipe_was, NULL);
        signal(SIGCHLD, SIG_DFL);
        if (pi[0] >= 0) dup2(pi[0], 0);
        if (po[1] >= 0) dup2(po[1], 1);
        if (pe[1] >= 0) dup2(pe[1], 2);
        else if (flags & CAPTURE_SAME) dup2(po[1], 2);
        if (*cwd && chdir(cwd) < 0) { int e = errno; (void)!write(ex[1], &e, sizeof e); _exit(127); }
        execve(av[0], av, ev);
        int e = errno;
        (void)!write(ex[1], &e, sizeof e);
        _exit(127);
    }
    close_fd(&ex[1]);
    close_fd(&po[1]); close_fd(&pe[1]); close_fd(&pi[0]);
    int e;
    int failed = pid < 0 || (ex[0] >= 0 && read_full(ex[0], &e, sizeof e) == 0);
    close_fd(&ex[0]);
    for (int i = 0; i < argc; i++) free(av[i]);
    for (int i = 0; i < envc; i++) free(ev[i]);
    free(av); free(ev); free(cwd);
    if (failed) {
        close_fd(&po[0]); close_fd(&pe[0]); close_fd(&pi[1]);
        if (pid > 0) while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) { }
        free(input); free(c);
        return frame(s, 'D', id, NULL, INT32_MIN);   /* not started (Relay.NOT_STARTED) */
    }
    c->pid = pid;
    c->out = po[0];
    c->err = pe[0];
    c->in = pi[1];
    if (c->out >= 0) nonblock(c->out);
    if (c->err >= 0) nonblock(c->err);
    if (c->in >= 0) { nonblock(c->in); if (c->inlen == 0) close_fd(&c->in); }
    c->next = commands;
    commands = c;
    return 0;
}

static void reap(void) {
    int st;
    pid_t p;
    while ((p = waitpid(-1, &st, WNOHANG)) > 0) {
        for (command *c = commands; c; c = c->next) {
            if (c->pid != p) continue;
            c->reaped = 1;
            c->status = WIFSIGNALED(st) ? 128 + WTERMSIG(st) : WIFEXITED(st) ? WEXITSTATUS(st) : 127;
        }
    }
}

/*
 * The relayed run: frames from the server (our output, reads of our input, commands to
 * run) and to it (signals, what we read, the commands' captured output and end), until
 * its 'X'. Returns the exit code, or 70 if the server went away.
 */
static int relay(int s) {
    static char buf[64 << 10];
    int32_t want = 0;                   /* an 'R' to answer: how much, at most */
    relay_sock = s;
    for (;;) {
        struct pollfd p[64];
        command *who[64];
        int n = 0;
        p[n++] = (struct pollfd){ s, POLLIN, 0 };
        p[n++] = (struct pollfd){ sigpipe_fds[0], POLLIN, 0 };
        if (want > 0) p[n++] = (struct pollfd){ 0, POLLIN, 0 };
        int fixed = n;
        for (command *c = commands; c && n < 61; c = c->next) {
            if (c->out >= 0) { who[n] = c; p[n++] = (struct pollfd){ c->out, POLLIN, 0 }; }
            if (c->err >= 0) { who[n] = c; p[n++] = (struct pollfd){ c->err, POLLIN, 0 }; }
            if (c->in >= 0) { who[n] = c; p[n++] = (struct pollfd){ c->in, POLLOUT, 0 }; }
        }
        /* frames already buffered: handle them before waiting */
        if (poll(p, (nfds_t)n, sb_at < sb_n ? 0 : -1) < 0) {
            if (errno == EINTR) continue;
            perror("rexxjs: poll");
            return 70;
        }
        if (p[1].revents & POLLIN) {
            unsigned char sig;
            if (read(sigpipe_fds[0], &sig, 1) == 1) {
                if (sig == SIGCHLD) reap();
                else {
                    unsigned char m[5] = { 'S', 0, 0, 0, sig };
                    write_all(s, m, 5);
                }
            }
        }
        if (want > 0 && (p[2].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t r = read(0, buf, (size_t)want);
            if (r < 0 && (errno == EINTR || errno == EAGAIN)) { /* again */ }
            else {
                want = 0;
                if (frame(s, 'I', 0, buf, r < 0 ? -1 : (int32_t)r) < 0) goto gone;
            }
        }
        for (int i = fixed; i < n; i++) {
            if (!p[i].revents) continue;
            command *c = who[i];
            if (p[i].fd == c->in) {
                ssize_t w = write(c->in, c->input + c->inat, c->inlen - c->inat);
                if (w > 0) c->inat += (size_t)w;
                if ((w < 0 && errno != EINTR && errno != EAGAIN) || c->inat == c->inlen) close_fd(&c->in);
                continue;
            }
            int *fd = p[i].fd == c->out ? &c->out : &c->err;
            ssize_t r = read(*fd, buf, sizeof buf);
            if (r < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            if (r <= 0) { close_fd(fd); continue; }
            if (frame(s, fd == &c->out ? 'o' : 'e', c->id, buf, (int32_t)r) < 0) goto gone;
        }
        /* the commands that ended and whose captured output has all gone */
        for (command **cp = &commands; *cp; ) {
            command *c = *cp;
            if (c->reaped && c->out < 0 && c->err < 0) {
                close_fd(&c->in);
                if (frame(s, 'D', c->id, NULL, c->status) < 0) goto gone;
                *cp = c->next;
                free(c->input);
                free(c);
            } else cp = &c->next;
        }
        if (!(p[0].revents & (POLLIN | POLLHUP | POLLERR)) && sb_at == sb_n) continue;

        /* a frame from the server: read whole (it writes each at once) */
        unsigned char t;
        if (read_full(s, &t, 1) < 0) goto gone;
        int32_t a, b;
        switch (t) {
        case 'O': case 'E':
            if (read32(s, &a) < 0 || a < 0 || a > (int32_t)sizeof buf || read_full(s, buf, (size_t)a) < 0) goto gone;
            if (out_all(t == 'O' ? 1 : 2, buf, (size_t)a) < 0 && errno == EPIPE) broken();
            break;
        case 'R':
            if (read32(s, &a) < 0) goto gone;
            want = a < 1 ? 1 : a > (int32_t)sizeof buf ? (int32_t)sizeof buf : a;
            break;
        case 'C':
            if (start_command(s) < 0) goto gone;
            break;
        case 'K':
            if (read32(s, &a) < 0 || read32(s, &b) < 0) goto gone;
            for (command *c = commands; c; c = c->next) if (c->id == a && !c->reaped) kill(c->pid, b);
            break;
        case 'X': {
            unsigned char x[12];
            if (read_full(s, x, 12) < 0) goto gone;
            return (int)get32(x) & 0xff;
        }
        default:
            fprintf(stderr, "rexxjs: unexpected frame from the server\n");
            return 70;
        }
    }
gone:
    fprintf(stderr, "rexxjs: the server went away\n");
    return 70;
}

/* ---- main ---- */

/*
 * rexxjs --orx-stdin-at OFFSET REPORT COMMAND [ARGS...]: what the server runs a
 * command through when the client's standard input is a regular file (Host.java). The
 * command gets the file as a new open file description (/proc/PID/fd/0), whose offset
 * is 0, where natively it would share the client's: so it is moved to OFFSET, where the
 * program's reading has reached, the command runs on a child, and the offset it leaves
 * is written to the file REPORT, from where the program goes on reading, as natively.
 * Exits as the command did (a signal is raised again on itself).
 */
static pid_t stdin_child;
static void stdin_forward(int sig) { if (stdin_child > 0) kill(stdin_child, sig); }

static int stdin_at(char **argv) {
    if (!argv[2] || !argv[3] || !argv[4]) { fputs("rexxjs: --orx-stdin-at OFFSET REPORT COMMAND...\n", stderr); return 2; }
    off_t at = (off_t)strtoll(argv[2], NULL, 10);
    if (at > 0) (void)lseek(0, at, SEEK_SET);
    signal(SIGINT, stdin_forward);
    signal(SIGTERM, stdin_forward);
    signal(SIGHUP, stdin_forward);
    stdin_child = fork();
    if (stdin_child < 0) { perror("rexxjs: fork"); return 127; }
    if (stdin_child == 0) {
        signal(SIGINT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        execvp(argv[4], argv + 4);
        _exit(127);
    }
    int st;
    while (waitpid(stdin_child, &st, 0) < 0) if (errno != EINTR) { st = 127 << 8; break; }
    off_t now = lseek(0, 0, SEEK_CUR);
    FILE *f = fopen(argv[3], "w");
    if (f) { fprintf(f, "%lld\n", (long long)now); fclose(f); }
    if (WIFSIGNALED(st)) {
        signal(WTERMSIG(st), SIG_DFL);
        kill(getpid(), WTERMSIG(st));
        return 128 + WTERMSIG(st);
    }
    return WIFEXITED(st) ? WEXITSTATUS(st) : 127;
}

/*
 * The request (Server.java): our pid and ppid, directory, arguments, environment (this
 * directory first in PATH, as rexxj puts it), the kinds and offsets of our standard
 * streams, and the mode, relayed or not.
 */
static int request(int s, char **argv, const char *kind, const int64_t *offset, int relayed) {
    buf b = { 0 };
    put(&b, "RXJ2", 4);
    put32(&b, (uint32_t)getpid());
    put32(&b, (uint32_t)getppid());
    char cwd[PATH_MAX];
    putstr(&b, getcwd(cwd, sizeof cwd) ? cwd : "/");
    int nargs = 0;
    for (char **a = argv + 1; *a; a++) nargs++;
    put32(&b, (uint32_t)nargs);
    for (char **a = argv + 1; *a; a++) putstr(&b, *a);
    const char *oldpath = getenv("PATH");
    char *newpath = malloc(strlen(dir) + (oldpath ? strlen(oldpath) : 0) + 8);
    sprintf(newpath, "PATH=%s%s%s", dir, oldpath ? ":" : "", oldpath ? oldpath : "");
    int nenv = 1;
    for (char **e = environ; *e; e++) if (strncmp(*e, "PATH=", 5) != 0) nenv++;
    put32(&b, (uint32_t)nenv);
    putstr(&b, newpath);
    for (char **e = environ; *e; e++) if (strncmp(*e, "PATH=", 5) != 0) putstr(&b, *e);
    free(newpath);
    for (int fd = 0; fd < 3; fd++) {
        put(&b, &kind[fd], 1);
        put64(&b, (uint64_t)offset[fd]);
    }
    put(&b, relayed ? "R" : "P", 1);
    int r = write_all(s, b.p, b.n);
    free(b.p);
    return r;
}

/* relayed, a command's end comes as SIGCHLD; a broken pipe to one, as EPIPE */
static void relay_signals(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART | SA_NOCLDSTOP;
    sigaction(SIGCHLD, &sa, NULL);
    sa.sa_handler = SIG_IGN;
    sa.sa_flags = 0;
    sigaction(SIGPIPE, &sa, &pipe_was);
}

int main(int argc, char **argv) {
    (void)argc;
    char self[PATH_MAX];
#ifdef __APPLE__
    char exe[PATH_MAX];
    uint32_t size = sizeof exe;
    if (_NSGetExecutablePath(exe, &size) != 0 || !realpath(exe, self)) { fputs("rexxjs: where am I?\n", stderr); return 70; }
#else
    ssize_t len = readlink("/proc/self/exe", self, sizeof self - 1);
    if (len <= 0) { perror("rexxjs: /proc/self/exe"); return 70; }
    self[len] = 0;
#endif
    strcpy(dir, self);
    char *slash = strrchr(dir, '/');
    if (slash) *slash = 0;

    if (argv[1] && strcmp(argv[1], "--orx-stdin-at") == 0) return stdin_at(argv);

    /* what only rexxj does: the API server's daemon */
    if (argv[1] && strcmp(argv[1], "--orx-wasm-rxapi") == 0) fallback(argv);
    /* rexxjs --orx-server-stop: ends this build's server (and the programs it runs) */
    int stop = argv[1] && strcmp(argv[1], "--orx-server-stop") == 0;

    /* relayed: where there is no /proc to open our streams through, or when asked */
#ifdef __linux__
    const char *rv = getenv("REXXJ_SERVER_RELAY");
    int relayed = rv && *rv;
#else
    int relayed = 1;
#endif

    /* the standard streams: kinds and offsets; closed, or a socket unless relayed: rexxj */
    char kind[3];
    int64_t offset[3];
    for (int fd = 0; fd < 3; fd++) {
        struct stat st;
        if ((fstat(fd, &st) < 0 || (S_ISSOCK(st.st_mode) && !relayed)) && !stop) fallback(argv);
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
    if (stop) {
        if (s < 0) { fprintf(stderr, "rexxjs: no server running\n"); return 1; }
        if (write_all(s, "RXJS", 4) < 0) { fprintf(stderr, "rexxjs: the server went away\n"); return 70; }
        char c;
        while (read(s, &c, 1) < 0 && errno == EINTR) { }    /* it closes the connection as it goes */
        fprintf(stderr, "rexxjs: server stopped\n");
        return 0;
    }
    if (s < 0) {
        int alive = start_server(path);
        struct timespec ts = { 0, 50 * 1000 * 1000 };
        for (int i = 0; i < 600 && s < 0; i++) {       /* up to 30 s */
            if (alive >= 0) {
                struct pollfd pf = { alive, POLLIN, 0 };
                char c;
                if (poll(&pf, 1, 50) > 0 && read(alive, &c, 1) == 0) {
                    /* the server we started has gone: another one may serve the socket
                       (ours found it taken), so one last try, then rexxj */
                    s = connect_to(path);
                    break;
                }
            } else {
                nanosleep(&ts, NULL);
            }
            s = connect_to(path);
        }
        if (alive >= 0) close(alive);
        if (s < 0) fallback(argv);
    }

    if (pipe_cloexec(sigpipe_fds) < 0) { perror("rexxjs"); return 70; }
    forward(SIGINT);
    forward(SIGTERM);
    forward(SIGHUP);
    if (relayed) relay_signals();
    if (request(s, argv, kind, offset, relayed) < 0) {
        fprintf(stderr, "rexxjs: the server went away\n");
        return 70;
    }
    if (relayed) return relay(s);

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
    if (ans[0] == 'N') {
        /* the server cannot open our streams (a pipe of another user's: sudo -u from a
           root shell), and has run nothing: the same, relayed through the socket */
        close(s);
        s = connect_to(path);
        if (s < 0) fallback(argv);
        relay_signals();
        if (request(s, argv, kind, offset, 1) < 0) {
            fprintf(stderr, "rexxjs: the server went away\n");
            return 70;
        }
        return relay(s);
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
