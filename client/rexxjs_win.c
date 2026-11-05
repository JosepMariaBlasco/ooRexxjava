/*
 * rexxjs.exe: rexxjs on Windows (client/rexxjs.c has it on Unix): runs a Rexx program
 * in a warm JVM (rexxjvm.Server) instead of starting one.
 *
 *   rexxjs [args...]          as rexxj [args...]
 *   rexxjs --orx-server-stop  ends this build's server, and the programs it runs
 *
 * The same protocol as on Unix, always relayed (Relay.java): Windows has no /proc for
 * the server to open the client's streams through. The socket is an AF_UNIX one
 * (Winsock, Windows 10 1803 and later), %TEMP%\.ooRexx-jvm-USER-server-KEY (KEY from
 * rexxj.jar's file index, size and time); if there is none, the server is started,
 * detached, with its output to SOCKET.log. The client sends its arguments (parsed from
 * its command line as the C runtime does), its environment (its directory first in
 * PATH, as rexxj.cmd puts it), current directory, pid and parent's pid, and what its
 * standard handles are; then it writes what the program writes, reads what it asks
 * for, and runs its commands with CreateProcess, with its own standard handles but for
 * those the program captures. Ctrl+C and Ctrl+Break are sent on as SIGINT (a HALT, as
 * natively), closing the console as SIGHUP, logoff and shutdown as SIGTERM. It exits
 * with the program's exit code.
 *
 * Where Unix uses poll() on everything, this uses threads: the main one reads the
 * server's frames; one reads standard input when the program asks; each command has
 * one that waits for it and one for each captured stream (and one to give it input).
 * Frames to the server are written under a lock, each at once.
 *
 * Whenever it cannot do that (no AF_UNIX, no server and none can start, a standard
 * handle that is missing, a socket path that is not ASCII or is too long, the API
 * server's daemon), it runs rexxj.cmd instead, with its command line as it got it.
 * rexxj.cmd runs rexxjs.exe when REXXJ_SERVER is set.
 *
 * Built with MinGW-w64: x86_64-w64-mingw32-gcc -O2 -municode -o rexxjs.exe
 *   rexxjs_win.c -lws2_32 -static
 */
#define WIN32_LEAN_AND_MEAN
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#include <winsock2.h>
#include <windows.h>
#include <afunix.h>
#include <shellapi.h>
#include <tlhelp32.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <wctype.h>

static wchar_t wdir[MAX_PATH * 2];          /* where rexxjs.exe, rexxj.cmd and rexxj.jar are */
static char *dir;                           /* the same, UTF-8 */
static HANDLE hin, hout, herr;              /* our standard handles */

static void die(const char *what) {
    fprintf(stderr, "rexxjs: %s (error %lu)\n", what, (unsigned long)GetLastError());
    ExitProcess(70);
}

static void *xmalloc(size_t n) {
    void *p = malloc(n ? n : 1);
    if (!p) die("out of memory");
    return p;
}

/* ---- UTF-8 and UTF-16 ---- */

static char *utf8(const wchar_t *w) {
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, NULL, 0, NULL, NULL);
    char *s = xmalloc((size_t)(n > 0 ? n : 1));
    if (n <= 0 || !WideCharToMultiByte(CP_UTF8, 0, w, -1, s, n, NULL, NULL)) s[0] = 0;
    return s;
}

static wchar_t *wide(const char *s) {
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    wchar_t *w = xmalloc(sizeof *w * (size_t)(n > 0 ? n : 1));
    if (n <= 0 || !MultiByteToWideChar(CP_UTF8, 0, s, -1, w, n)) w[0] = 0;
    return w;
}

/* a growing wide string */
typedef struct { wchar_t *p; size_t n, cap; } wbuf;

static void wput(wbuf *b, const wchar_t *s, size_t n) {
    if (b->n + n + 1 > b->cap) {
        b->cap = (b->n + n + 1) * 2 + 64;
        b->p = realloc(b->p, b->cap * sizeof *b->p);
        if (!b->p) die("out of memory");
    }
    memcpy(b->p + b->n, s, n * sizeof *s);
    b->n += n;
    b->p[b->n] = 0;
}

static void wputs(wbuf *b, const wchar_t *s) { wput(b, s, wcslen(s)); }

/*
 * One argument on a command line, so that the C runtime's parsing (CommandLineToArgvW)
 * gives it back: as it is if it has no blank or quote; else quoted, with the backslashes
 * before a quote doubled. One already in quotes goes as it is, as Java's ProcessBuilder
 * passes it (Host.windows gives cmd.exe /s /c "command" so).
 */
static void quote_arg(wbuf *b, const wchar_t *a) {
    size_t n = wcslen(a);
    if (n > 0 && !wcspbrk(a, L" \t\n\v\"")) { wput(b, a, n); return; }
    if (n >= 2 && a[0] == L'"' && a[n - 1] == L'"') { wput(b, a, n); return; }
    wputs(b, L"\"");
    for (size_t i = 0; ; i++) {
        size_t slashes = 0;
        while (i < n && a[i] == L'\\') { slashes++; i++; }
        if (i == n) {                       /* before the closing quote: doubled */
            for (size_t k = 0; k < slashes * 2; k++) wputs(b, L"\\");
            break;
        }
        if (a[i] == L'"') {
            for (size_t k = 0; k < slashes * 2 + 1; k++) wputs(b, L"\\");
            wputs(b, L"\"");
        } else {
            for (size_t k = 0; k < slashes; k++) wputs(b, L"\\");
            wput(b, a + i, 1);
        }
    }
    wputs(b, L"\"");
}

/* ---- falling back to rexxj.cmd ---- */

/* our command line without the program's name */
static const wchar_t *arguments_tail(void) {
    const wchar_t *c = GetCommandLineW();
    if (*c == L'"') {
        c++;
        while (*c && *c != L'"') c++;
        if (*c) c++;
    } else {
        while (*c && *c != L' ' && *c != L'\t') c++;
    }
    while (*c == L' ' || *c == L'\t') c++;
    return c;
}

/* REXXJS_DEBUG set: why rexxjs.exe falls back, or cannot start a command, to stderr */
static int debug(void) {
    static int d = -1;
    if (d < 0) { const wchar_t *v = _wgetenv(L"REXXJS_DEBUG"); d = v && *v; }
    return d;
}

#define fallback() fallback_at(__LINE__)
static void fallback_at(int line) {
    if (debug()) fprintf(stderr, "rexxjs: falling back to rexxj.cmd (rexxjs_win.c:%d, error %lu)\n", line, (unsigned long)GetLastError());
    wbuf cl = { 0 };
    wputs(&cl, L"\"");
    wputs(&cl, wdir);
    wputs(&cl, L"\\rexxj.cmd\" ");
    wputs(&cl, arguments_tail());
    SetEnvironmentVariableW(L"REXXJ_SERVER", NULL);   /* rexxj.cmd must not come back here */
    STARTUPINFOW si = { sizeof si };
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(NULL, cl.p, NULL, NULL, TRUE, 0, NULL, NULL, &si, &pi)) die("cannot run rexxj.cmd");
    SetConsoleCtrlHandler(NULL, TRUE);              /* Ctrl+C is rexxj's now */
    CloseHandle(pi.hThread);
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD rc = 70;
    GetExitCodeProcess(pi.hProcess, &rc);
    ExitProcess(rc);
}

/* ---- the request ---- */

typedef struct { char *p; size_t n, cap; } buf;

static void put(buf *b, const void *d, size_t n) {
    if (b->n + n > b->cap) {
        b->cap = (b->n + n) * 2 + 256;
        b->p = realloc(b->p, b->cap);
        if (!b->p) die("out of memory");
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

/* ---- the socket ---- */

static SOCKET sock = INVALID_SOCKET;
static CRITICAL_SECTION wlock;              /* one frame at a time to the server */

static int send_all(const void *p, size_t n) {
    const char *c = p;
    while (n > 0) {
        int w = send(sock, c, n > (1 << 30) ? (1 << 30) : (int)n, 0);
        if (w <= 0) return -1;
        c += w;
        n -= (size_t)w;
    }
    return 0;
}

static SOCKET connect_to(const char *path) {
    SOCKET s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s == INVALID_SOCKET) return s;
    SetHandleInformation((HANDLE)s, HANDLE_FLAG_INHERIT, 0);   /* not for the commands */
    struct sockaddr_un a;
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path, path, sizeof a.sun_path - 1);
    if (connect(s, (struct sockaddr *)&a, sizeof a) != 0) { closesocket(s); return INVALID_SOCKET; }
    return s;
}

/* the server's frames come through a buffer: a read for many of them */
static unsigned char sbuf[128 << 10];
static size_t sb_at, sb_n;

static int read_full(void *p, size_t n) {
    char *c = p;
    while (n > 0) {
        if (sb_at == sb_n) {
            int r = recv(sock, (char *)sbuf, (int)sizeof sbuf, 0);
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

static uint32_t get32(const unsigned char *c) {
    return ((uint32_t)c[0] << 24) | ((uint32_t)c[1] << 16) | ((uint32_t)c[2] << 8) | c[3];
}

static int read32(int32_t *v) {
    unsigned char c[4];
    if (read_full(c, 4) < 0) return -1;
    *v = (int32_t)get32(c);
    return 0;
}

static char *readstr(void) {
    int32_t n;
    if (read32(&n) < 0 || n < 0 || n > (64 << 20)) return NULL;
    char *p = xmalloc((size_t)n + 1);
    if (read_full(p, (size_t)n) < 0) { free(p); return NULL; }
    p[n] = 0;
    return p;
}

/* A frame to the server, written at once (Relay.java has them). */
static int frame(unsigned char type, int32_t id, const void *p, int32_t n) {
    unsigned char h[9] = { type };
    size_t hn = 1;
    if (type == 'o' || type == 'e' || type == 'D') {
        h[1] = id >> 24; h[2] = id >> 16; h[3] = id >> 8; h[4] = id;
        hn = 5;
    }
    h[hn] = n >> 24; h[hn + 1] = n >> 16; h[hn + 2] = n >> 8; h[hn + 3] = n;
    hn += 4;
    EnterCriticalSection(&wlock);
    int r = send_all(h, hn);
    if (r == 0 && p && n > 0) r = send_all(p, (size_t)n);
    LeaveCriticalSection(&wlock);
    return r;
}

/* ---- the server ---- */

/* Starts the server, detached, its output to SOCKET.log. Returns its process, so that the
   client stops waiting for its socket if it ends (it cannot run: Java before 16, a bad
   option...); NULL if it could not be started. */
static HANDLE start_server(const char *path) {
    wchar_t *wpath = wide(path);
    wbuf log = { 0 };
    wputs(&log, wpath);
    wputs(&log, L".log");
    SECURITY_ATTRIBUTES sa = { sizeof sa, NULL, TRUE };
    HANDLE out = CreateFileW(log.p, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                             &sa, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    HANDLE null = CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, &sa, OPEN_EXISTING, 0, NULL);
    if (out == INVALID_HANDLE_VALUE || null == INVALID_HANDLE_VALUE) return NULL;

    wbuf cl = { 0 };
    wputs(&cl, L"java -Xss16m ");
    const wchar_t *opts = _wgetenv(L"REXXJ_JAVA_OPTIONS");     /* as rexxj.cmd: as they are */
    if (opts && *opts) { wputs(&cl, opts); wputs(&cl, L" "); }
    wputs(&cl, L"\"-Drexxjvm.executable="); wputs(&cl, wdir); wputs(&cl, L"\\rexx.cmd\" ");
    wputs(&cl, L"\"-Drexxjvm.home="); wputs(&cl, wdir); wputs(&cl, L"\" ");
    wputs(&cl, L"\"-javaagent:"); wputs(&cl, wdir); wputs(&cl, L"\\rexxj.jar\" ");
    /* rexxj.jar alone: each run has a class loader of its own for BSF4ooRexx and the
       client's CLASSPATH (rexxjvm.Server) */
    wputs(&cl, L"-cp \""); wputs(&cl, wdir); wputs(&cl, L"\\rexxj.jar\" rexxjvm.Server ");
    quote_arg(&cl, wpath);

    /* only the log and NUL are inherited */
    HANDLE list[2] = { null, out };
    SIZE_T size = 0;
    InitializeProcThreadAttributeList(NULL, 1, 0, &size);
    LPPROC_THREAD_ATTRIBUTE_LIST attrs = xmalloc(size);
    if (!InitializeProcThreadAttributeList(attrs, 1, 0, &size)
        || !UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, list, sizeof list, NULL, NULL)) return NULL;
    STARTUPINFOEXW si;
    memset(&si, 0, sizeof si);
    si.StartupInfo.cb = sizeof si;
    si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    si.StartupInfo.hStdInput = null;
    si.StartupInfo.hStdOutput = out;
    si.StartupInfo.hStdError = out;
    si.lpAttributeList = attrs;
    /* its directory: the temporary one, not ours (a process's directory cannot be removed) */
    wchar_t tmp[MAX_PATH + 1];
    DWORD tn = GetTempPathW(MAX_PATH + 1, tmp);
    PROCESS_INFORMATION pi;
    DWORD flags = DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP | EXTENDED_STARTUPINFO_PRESENT;
    /* out of our job, if it lets us: a job that ends with its processes (a CI step's)
       would take the server with it */
    BOOL ok = CreateProcessW(NULL, cl.p, NULL, NULL, TRUE, flags | CREATE_BREAKAWAY_FROM_JOB, NULL,
                             tn ? tmp : NULL, &si.StartupInfo, &pi)
           || CreateProcessW(NULL, cl.p, NULL, NULL, TRUE, flags, NULL, tn ? tmp : NULL, &si.StartupInfo, &pi);
    if (ok) CloseHandle(pi.hThread);
    DeleteProcThreadAttributeList(attrs);
    CloseHandle(out);
    CloseHandle(null);
    return ok ? pi.hProcess : NULL;
}

/* ---- Ctrl+C and the rest: sent on to the program ---- */

static BOOL WINAPI on_ctrl(DWORD type) {
    int sig = type == CTRL_C_EVENT || type == CTRL_BREAK_EVENT ? 2 : type == CTRL_CLOSE_EVENT ? 1 : 15;
    unsigned char m[5] = { 'S', 0, 0, 0, (unsigned char)sig };
    EnterCriticalSection(&wlock);
    send_all(m, 5);
    LeaveCriticalSection(&wlock);
    /* closing, logoff, shutdown: the process ends when this returns; give the
       program time to end and the main thread to exit with its code */
    if (sig != 2) Sleep(4000);
    return TRUE;
}

/* ---- standard input, read when the program asks ---- */

static HANDLE want_event;
static volatile LONG want;
static char inbuf[64 << 10];

static DWORD WINAPI stdin_reader(LPVOID unused) {
    (void)unused;
    for (;;) {
        WaitForSingleObject(want_event, INFINITE);
        DWORD n = 0;
        int32_t r;
        for (;;) {
            if (ReadFile(hin, inbuf, (DWORD)want, &n, NULL)) {
                /* Ctrl+C during a console read: no data, and no end of file; the HALT
                   comes as a signal */
                if (n == 0 && GetLastError() == ERROR_OPERATION_ABORTED) { SetLastError(0); Sleep(50); continue; }
                r = (int32_t)n;
            } else {
                DWORD e = GetLastError();
                r = e == ERROR_BROKEN_PIPE || e == ERROR_HANDLE_EOF ? 0 : -1;
            }
            break;
        }
        if (frame('I', 0, inbuf, r) < 0) return 0;
    }
}

/* ---- commands ---- */

#define CAPTURE_OUT 1
#define CAPTURE_ERR 2
#define CAPTURE_SAME 4
#define GIVE_INPUT 8

typedef struct command {
    int32_t id;
    HANDLE process;
    HANDLE out, err, in;                /* our ends of its pipes; NULL if not captured */
    char *input; size_t inlen;
    struct command *next;
} command;

static command *commands;
static CRITICAL_SECTION clock_;          /* the list of commands */

typedef struct { command *c; HANDLE h; unsigned char type; } pump;

static DWORD WINAPI pump_out(LPVOID p) {
    pump *u = p;
    char *b = xmalloc(64 << 10);
    DWORD n;
    while (ReadFile(u->h, b, 64 << 10, &n, NULL) && n > 0)
        if (frame(u->type, u->c->id, b, (int32_t)n) < 0) break;
    free(b);
    return 0;
}

static DWORD WINAPI pump_in(LPVOID p) {
    command *c = p;
    size_t at = 0;
    while (at < c->inlen) {
        DWORD w;
        DWORD k = c->inlen - at > (1u << 20) ? (1u << 20) : (DWORD)(c->inlen - at);
        if (!WriteFile(c->in, c->input + at, k, &w, NULL)) break;
        at += w;
    }
    CloseHandle(c->in);
    return 0;
}

/* Waits for the command and for its captured output to go, then says it ended. */
static DWORD WINAPI waiter(LPVOID p) {
    command *c = p;
    HANDLE t[3];
    int nt = 0;
    pump po = { c, c->out, 'o' }, pe = { c, c->err, 'e' };
    if (c->in) t[nt++] = CreateThread(NULL, 64 << 10, pump_in, c, 0, NULL);
    if (c->out) t[nt++] = CreateThread(NULL, 0, pump_out, &po, 0, NULL);
    if (c->err) t[nt++] = CreateThread(NULL, 0, pump_out, &pe, 0, NULL);
    WaitForSingleObject(c->process, INFINITE);
    DWORD status = 127;
    GetExitCodeProcess(c->process, &status);
    /* its output, until whatever holds the pipes (it, or what it started) closes them */
    for (int i = 0; i < nt; i++) if (t[i]) { WaitForSingleObject(t[i], INFINITE); CloseHandle(t[i]); }
    frame('D', c->id, NULL, (int32_t)status);
    EnterCriticalSection(&clock_);
    for (command **cp = &commands; *cp; cp = &(*cp)->next) if (*cp == c) { *cp = c->next; break; }
    LeaveCriticalSection(&clock_);
    if (c->out) CloseHandle(c->out);
    if (c->err) CloseHandle(c->err);
    CloseHandle(c->process);
    free(c->input);
    free(c);
    return 0;
}

/* the length of an environment string's name (a leading '=' is part of it: "=C:=C:\\x") */
static size_t env_name(const wchar_t *e) {
    const wchar_t *eq = wcschr(e + (*e == L'='), L'=');
    return eq ? (size_t)(eq - e) : wcslen(e);
}

static int cmp_env(const void *a, const void *b) {
    /* by name, case-insensitively (as upper case), as Windows wants an environment block */
    const wchar_t *x = *(wchar_t *const *)a, *y = *(wchar_t *const *)b;
    size_t nx = env_name(x), ny = env_name(y);
    for (size_t i = 0; i < nx && i < ny; i++) {
        wint_t cx = towupper(x[i]), cy = towupper(y[i]);
        if (cx != cy) return cx < cy ? -1 : 1;
    }
    return nx < ny ? -1 : nx > ny ? 1 : 0;
}

/* an inheritable copy of one of our handles, for a command */
static HANDLE inheritable(HANDLE h) {
    HANDLE d = NULL;
    if (!DuplicateHandle(GetCurrentProcess(), h, GetCurrentProcess(), &d, 0, TRUE, DUPLICATE_SAME_ACCESS)) return NULL;
    return d;
}

static HANDLE pipe_for(HANDLE *ours, int child_reads) {
    SECURITY_ATTRIBUTES sa = { sizeof sa, NULL, TRUE };
    HANDLE r, w;
    if (!CreatePipe(&r, &w, &sa, 0)) return NULL;
    *ours = child_reads ? w : r;
    SetHandleInformation(*ours, HANDLE_FLAG_INHERIT, 0);
    return child_reads ? r : w;
}

/* 'C': starts the command, with our standard handles but for those captured */
static int start_command(void) {
    int32_t id, argc, envc, flags, inlen;
    if (read32(&id) < 0 || read32(&argc) < 0 || argc < 1 || argc > 1 << 20) return -1;
    char **av = calloc((size_t)argc, sizeof *av);
    for (int i = 0; i < argc; i++) if (!(av[i] = readstr())) return -1;
    if (read32(&envc) < 0 || envc < 0 || envc > 1 << 20) return -1;
    wchar_t **ev = calloc((size_t)envc + 1, sizeof *ev);
    for (int i = 0; i < envc; i++) {
        char *e = readstr();
        if (!e) return -1;
        ev[i] = wide(e);
        free(e);
    }
    char *cwd = readstr();
    if (!cwd || read32(&flags) < 0 || read32(&inlen) < 0 || inlen < 0) return -1;
    char *input = xmalloc((size_t)inlen + 1);
    if (read_full(input, (size_t)inlen) < 0) return -1;

    wbuf cl = { 0 };
    for (int i = 0; i < argc; i++) {
        wchar_t *a = wide(av[i]);
        if (i) wputs(&cl, L" ");
        if (i == 0 && !wcschr(a, L' ') && !wcschr(a, L'\t') && *a) wputs(&cl, a);
        else if (i == 0) { wputs(&cl, L"\""); wputs(&cl, a); wputs(&cl, L"\""); }
        else quote_arg(&cl, a);
        free(a);
        free(av[i]);
    }
    free(av);
    qsort(ev, (size_t)envc, sizeof *ev, cmp_env);
    wbuf block = { 0 };
    for (int i = 0; i < envc; i++) { wput(&block, ev[i], wcslen(ev[i]) + 1); free(ev[i]); }
    wput(&block, L"\0", 1);
    free(ev);
    wchar_t *wcwd = *cwd ? wide(cwd) : NULL;
    free(cwd);

    command *c = calloc(1, sizeof *c);
    c->id = id;
    c->input = input;
    c->inlen = (size_t)inlen;
    HANDLE cin = NULL, cout = NULL, cerr = NULL;     /* the command's, inheritable */
    int ok = 1;
    if (flags & GIVE_INPUT) ok &= (cin = pipe_for(&c->in, 1)) != NULL;
    else ok &= (cin = inheritable(hin)) != NULL;
    if (flags & CAPTURE_OUT) ok &= (cout = pipe_for(&c->out, 0)) != NULL;
    else ok &= (cout = inheritable(hout)) != NULL;
    if (flags & CAPTURE_ERR) ok &= (cerr = pipe_for(&c->err, 0)) != NULL;
    else if ((flags & CAPTURE_SAME) && cout) ok &= (cerr = inheritable(cout)) != NULL;
    else ok &= (cerr = inheritable(herr)) != NULL;

    BOOL started = FALSE;
    PROCESS_INFORMATION pi;
    if (ok) {
        /* only its three handles are inherited, not those of the other commands */
        HANDLE list[3] = { cin, cout, cerr };
        SIZE_T size = 0;
        InitializeProcThreadAttributeList(NULL, 1, 0, &size);
        LPPROC_THREAD_ATTRIBUTE_LIST attrs = xmalloc(size);
        if (InitializeProcThreadAttributeList(attrs, 1, 0, &size)
            && UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, list, sizeof list, NULL, NULL)) {
            STARTUPINFOEXW si;
            memset(&si, 0, sizeof si);
            si.StartupInfo.cb = sizeof si;
            si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
            si.StartupInfo.hStdInput = cin;
            si.StartupInfo.hStdOutput = cout;
            si.StartupInfo.hStdError = cerr;
            si.lpAttributeList = attrs;
            started = CreateProcessW(NULL, cl.p, NULL, NULL, TRUE,
                                     CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT,
                                     block.p, wcwd, &si.StartupInfo, &pi);
            DeleteProcThreadAttributeList(attrs);
        }
        free(attrs);
    }
    DWORD error = GetLastError();
    if (!started && debug()) {
        char *u = utf8(cl.p);
        fprintf(stderr, "rexxjs: cannot start [%s] (%s, error %lu)\n", u, ok ? "CreateProcess" : "handles", (unsigned long)error);
        free(u);
    }
    if (cin) CloseHandle(cin);
    if (cout) CloseHandle(cout);
    if (cerr) CloseHandle(cerr);
    free(cl.p);
    free(block.p);
    free(wcwd);
    if (!started) {
        if (c->in) CloseHandle(c->in);
        if (c->out) CloseHandle(c->out);
        if (c->err) CloseHandle(c->err);
        free(c->input);
        free(c);
        return frame('D', id, NULL, INT32_MIN);      /* not started (Relay.NOT_STARTED) */
    }
    CloseHandle(pi.hThread);
    c->process = pi.hProcess;
    if (c->in && c->inlen == 0) { CloseHandle(c->in); c->in = NULL; }
    EnterCriticalSection(&clock_);
    c->next = commands;
    commands = c;
    LeaveCriticalSection(&clock_);
    HANDLE t = CreateThread(NULL, 64 << 10, waiter, c, 0, NULL);
    if (t) CloseHandle(t);
    else return -1;
    return 0;
}

/* ---- the relayed run ---- */

static int write_out(HANDLE h, const char *p, DWORD n) {
    while (n > 0) {
        DWORD w;
        if (!WriteFile(h, p, n, &w, NULL)) return -1;
        p += w;
        n -= w;
    }
    return 0;
}

/*
 * The server's frames (our output, reads of our input, commands to run), until its
 * 'X'. Returns the exit code, or 70 if the server went away.
 */
static int relay(void) {
    static char b[64 << 10];
    want_event = CreateEventW(NULL, FALSE, FALSE, NULL);
    HANDLE t = CreateThread(NULL, 64 << 10, stdin_reader, NULL, 0, NULL);
    if (!want_event || !t) die("cannot start a thread");
    CloseHandle(t);
    for (;;) {
        unsigned char type;
        int32_t a, s;
        if (read_full(&type, 1) < 0) goto gone;
        switch (type) {
        case 'O': case 'E':
            if (read32(&a) < 0 || a < 0 || a > (int32_t)sizeof b || read_full(b, (size_t)a) < 0) goto gone;
            write_out(type == 'O' ? hout : herr, b, (DWORD)a);     /* lost if it fails, as Java's */
            break;
        case 'R':
            if (read32(&a) < 0) goto gone;
            want = a < 1 ? 1 : a > (int32_t)sizeof inbuf ? (int32_t)sizeof inbuf : a;
            SetEvent(want_event);
            break;
        case 'C':
            if (start_command() < 0) goto gone;
            break;
        case 'K':
            if (read32(&a) < 0 || read32(&s) < 0) goto gone;
            EnterCriticalSection(&clock_);
            for (command *c = commands; c; c = c->next) if (c->id == a) TerminateProcess(c->process, 128 + (UINT)s);
            LeaveCriticalSection(&clock_);
            break;
        case 'X': {
            unsigned char x[12];
            if (read_full(x, 12) < 0) goto gone;
            return (int)get32(x);
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

static DWORD parent_pid(void) {
    DWORD self = GetCurrentProcessId(), parent = 0;
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;
    PROCESSENTRY32W pe = { sizeof pe };
    for (BOOL more = Process32FirstW(snap, &pe); more; more = Process32NextW(snap, &pe))
        if (pe.th32ProcessID == self) { parent = pe.th32ParentProcessID; break; }
    CloseHandle(snap);
    return parent;
}

int wmain(int argc, wchar_t **wargv) {
    (void)argc; (void)wargv;
    DWORD n = GetModuleFileNameW(NULL, wdir, MAX_PATH * 2);
    if (n == 0 || n >= MAX_PATH * 2) die("where am I?");
    wchar_t *slash = wcsrchr(wdir, L'\\');
    if (slash) *slash = 0;
    dir = utf8(wdir);
    InitializeCriticalSection(&wlock);
    InitializeCriticalSection(&clock_);

    /* the arguments, as the C runtime parses the command line (as java.exe gets them) */
    int nargs = 0;
    wchar_t **args = CommandLineToArgvW(GetCommandLineW(), &nargs);
    if (!args) die("cannot parse the command line");
    const wchar_t *a1 = nargs > 1 ? args[1] : NULL;

    if (a1 && wcscmp(a1, L"--orx-stdin-at") == 0) {
        fputs("rexxjs: --orx-stdin-at is for the server's /proc mode, not on Windows\n", stderr);
        return 2;
    }
    /* what only rexxj does: the API server's daemon */
    if (a1 && wcscmp(a1, L"--orx-wasm-rxapi") == 0) fallback();
    /* rexxjs --orx-server-stop: ends this build's server (and the programs it runs) */
    int stop = a1 && wcscmp(a1, L"--orx-server-stop") == 0;

    /* the standard handles: kinds and offsets; a missing one: rexxj */
    hin = GetStdHandle(STD_INPUT_HANDLE);
    hout = GetStdHandle(STD_OUTPUT_HANDLE);
    herr = GetStdHandle(STD_ERROR_HANDLE);
    HANDLE std[3] = { hin, hout, herr };
    char kind[3];
    int64_t offset[3];
    for (int fd = 0; fd < 3; fd++) {
        HANDLE h = std[fd];
        offset[fd] = -1;
        kind[fd] = 'P';
        if (h == NULL || h == INVALID_HANDLE_VALUE) {
            if (!stop) fallback();
            continue;
        }
        SetLastError(0);
        DWORD type = GetFileType(h);
        DWORD mode;
        if (type == FILE_TYPE_UNKNOWN && GetLastError() != NO_ERROR) {
            if (!stop) fallback();
        } else if (type == FILE_TYPE_CHAR) {
            kind[fd] = GetConsoleMode(h, &mode) ? 'T' : 'P';         /* NUL is a character device too */
        } else if (type == FILE_TYPE_DISK) {
            LARGE_INTEGER zero = { 0 }, at;
            kind[fd] = 'F';
            if (SetFilePointerEx(h, zero, &at, FILE_CURRENT)) offset[fd] = at.QuadPart;
        }
    }

    /* the socket: this user, this build */
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) fallback();
    wbuf jar = { 0 };
    wputs(&jar, wdir);
    wputs(&jar, L"\\rexxj.jar");
    HANDLE jh = CreateFileW(jar.p, FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                            NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    BY_HANDLE_FILE_INFORMATION ji;
    if (jh == INVALID_HANDLE_VALUE || !GetFileInformationByHandle(jh, &ji)) fallback();
    CloseHandle(jh);
    wchar_t wtmp[MAX_PATH + 1];
    DWORD tn = GetTempPathW(MAX_PATH + 1, wtmp);
    if (tn == 0 || tn > MAX_PATH) fallback();
    if (wtmp[tn - 1] == L'\\') wtmp[tn - 1] = 0;
    char *tmp = utf8(wtmp);
    wchar_t wuser[256];
    DWORD un = 256;
    char user[64];
    if (GetUserNameW(wuser, &un)) {
        char *u = utf8(wuser);
        snprintf(user, sizeof user, "%s", u);
        free(u);
    } else {
        strcpy(user, "user");
    }
    for (char *c = user; *c; c++)
        if (!((*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || (*c >= '0' && *c <= '9') || *c == '.' || *c == '_' || *c == '-')) *c = '_';
    char path[512];
    unsigned long long index = ((unsigned long long)ji.nFileIndexHigh << 32) | ji.nFileIndexLow;
    unsigned long long size = ((unsigned long long)ji.nFileSizeHigh << 32) | ji.nFileSizeLow;
    unsigned long long mtime = ((unsigned long long)ji.ftLastWriteTime.dwHighDateTime << 32) | ji.ftLastWriteTime.dwLowDateTime;
    snprintf(path, sizeof path, "%s\\.ooRexx-jvm-%s-server-%llx%llx%llx", tmp, user, index, size, mtime / 10000000ULL);
    /* AF_UNIX paths: ASCII here (Java and Winsock agree on it), and short */
    for (const char *c = path; *c; c++) if ((unsigned char)*c >= 0x80) fallback();
    if (strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) fallback();

    sock = connect_to(path);
    if (stop) {
        if (sock == INVALID_SOCKET) { fprintf(stderr, "rexxjs: no server running\n"); return 1; }
        if (send_all("RXJS", 4) < 0) { fprintf(stderr, "rexxjs: the server went away\n"); return 70; }
        char c;
        recv(sock, &c, 1, 0);                /* it closes the connection as it goes */
        fprintf(stderr, "rexxjs: server stopped\n");
        return 0;
    }
    if (sock == INVALID_SOCKET) {
        /* no AF_UNIX at all (Windows before 10 1803): rexxj */
        SOCKET probe = socket(AF_UNIX, SOCK_STREAM, 0);
        if (probe == INVALID_SOCKET) fallback();
        closesocket(probe);
        HANDLE server = start_server(path);
        for (int i = 0; i < 600 && sock == INVALID_SOCKET; i++) {       /* up to 30 s */
            if (server && WaitForSingleObject(server, 50) == WAIT_OBJECT_0) {
                /* the server we started has ended: another one may serve the socket
                   (ours found it taken), so one last try, then rexxj */
                sock = connect_to(path);
                break;
            }
            if (!server) Sleep(50);
            sock = connect_to(path);
        }
        if (server) CloseHandle(server);
        if (sock == INVALID_SOCKET) fallback();
    }

    /* the request */
    buf b = { 0 };
    put(&b, "RXJ2", 4);
    put32(&b, (uint32_t)GetCurrentProcessId());
    put32(&b, (uint32_t)parent_pid());
    wchar_t wcwd[32768];
    DWORD cn = GetCurrentDirectoryW(32768, wcwd);
    char *cwd = utf8(cn && cn < 32768 ? wcwd : L"C:\\");
    putstr(&b, cwd);
    put32(&b, (uint32_t)(nargs > 1 ? nargs - 1 : 0));
    for (int i = 1; i < nargs; i++) {
        char *a = utf8(args[i]);
        putstr(&b, a);
        free(a);
    }
    /* the environment, with this directory first in PATH, as rexxj.cmd puts it */
    wchar_t *env = GetEnvironmentStringsW();
    int nenv = 0, haspath = 0;
    for (wchar_t *e = env; *e; e += wcslen(e) + 1) {
        nenv++;
        if (_wcsnicmp(e, L"PATH=", 5) == 0) haspath = 1;
    }
    put32(&b, (uint32_t)(nenv + !haspath));
    if (!haspath) {
        buf p = { 0 };
        put(&p, "PATH=", 5);
        put(&p, dir, strlen(dir) + 1);
        putstr(&b, p.p);
        free(p.p);
    }
    for (wchar_t *e = env; *e; e += wcslen(e) + 1) {
        char *u = utf8(e);
        if (_wcsnicmp(e, L"PATH=", 5) == 0) {
            buf p = { 0 };
            put(&p, u, 5);                  /* the name, in its own case */
            put(&p, dir, strlen(dir));
            if (u[5]) { put(&p, ";", 1); put(&p, u + 5, strlen(u + 5)); }
            put(&p, "", 1);
            putstr(&b, p.p);
            free(p.p);
        } else {
            putstr(&b, u);
        }
        free(u);
    }
    FreeEnvironmentStringsW(env);
    for (int fd = 0; fd < 3; fd++) {
        put(&b, &kind[fd], 1);
        put64(&b, (uint64_t)offset[fd]);
    }
    put(&b, "R", 1);

    SetConsoleCtrlHandler(on_ctrl, TRUE);
    EnterCriticalSection(&wlock);
    int sent = send_all(b.p, b.n);
    LeaveCriticalSection(&wlock);
    if (sent < 0) {
        fprintf(stderr, "rexxjs: the server went away\n");
        return 70;
    }
    ExitProcess((UINT)relay());
}
