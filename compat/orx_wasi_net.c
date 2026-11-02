/*
 * IPv4 sockets for rxsock under WASI preview 1, which has none (see
 * orx_wasi_net.h), through the Java launcher (import module "rexxjvm",
 * host/.../Net.java).
 *
 * The launcher keeps the channels; this side keeps what rxsock expects to set
 * and read back as on a native system: the socket's type, blocking mode and
 * options (all of them, so that getsockopt returns what setsockopt set, even
 * for the options Java has no notion of: SO_DEBUG, SO_DONTROUTE...); the ones
 * Java can apply are passed on. SO_RCVTIMEO and SO_SNDTIMEO become the waits
 * of the launcher's blocking calls; FIONBIO makes them not wait at all.
 *
 * Compiled without orx_wasi_compat.h.
 */

#include <errno.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include "orx_wasi_sockdefs.h"
#include <sys/select.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>

#define ORX_WASI_SOCKET_BASE 0x10000
#define MAX_SOCKETS 4096
#define MAX_OPTIONS 24

#define RXJ_IMPORT(name) __attribute__((import_module("rexxjvm"), import_name(#name)))

RXJ_IMPORT(net_socket) int rxj_net_socket(int stream);
RXJ_IMPORT(net_bind) int rxj_net_bind(int h, int addr, int port);
RXJ_IMPORT(net_listen) int rxj_net_listen(int h, int backlog);
RXJ_IMPORT(net_connect) int rxj_net_connect(int h, int addr, int port, int timeout);
RXJ_IMPORT(net_accept) int rxj_net_accept(int h, int timeout, int32_t *out);
RXJ_IMPORT(net_send) int rxj_net_send(int h, const void *buf, int len, int flags, int timeout);
RXJ_IMPORT(net_recv) int rxj_net_recv(int h, void *buf, int len, int flags, int timeout);
RXJ_IMPORT(net_sendto) int rxj_net_sendto(int h, const void *buf, int len, int addr, int port, int unused);
RXJ_IMPORT(net_recvfrom) int rxj_net_recvfrom(int h, void *buf, int len, int timeout, int32_t *out);
RXJ_IMPORT(net_shutdown) int rxj_net_shutdown(int h, int how);
RXJ_IMPORT(net_close) int rxj_net_close(int h);
RXJ_IMPORT(net_name) int rxj_net_name(int h, int peer, int32_t *out);
RXJ_IMPORT(net_setopt) int rxj_net_setopt(int h, int opt, int value);
RXJ_IMPORT(net_available) int rxj_net_available(int h);
RXJ_IMPORT(net_select) int rxj_net_select(int32_t *r, int nr, int32_t *w, int nw, int timeout, int unused);
RXJ_IMPORT(net_resolve) int rxj_net_resolve(int mode, const char *arg, char *out, int size);

/* Net.java's option codes */
enum { O_REUSEADDR = 1, O_KEEPALIVE = 2, O_RCVBUF = 3, O_SNDBUF = 4, O_LINGER = 5, O_BROADCAST = 6,
       O_OOBINLINE = 7, O_NODELAY = 8 };

typedef struct
{
    int used;
    int type;                      /* SOCK_STREAM, SOCK_DGRAM */
    int nonblocking;
    int rcvtimeo, sndtimeo;        /* milliseconds, 0 none */
    struct linger linger;
    int nopts;
    struct { int level, name, value; } opts[MAX_OPTIONS];
} SocketInfo;

static SocketInfo sockets[MAX_SOCKETS];
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

/* Net.java's error codes to errno */
static int net_errno(int code)
{
    switch (code)
    {
        case 1:  return EBADF;
        case 2:  return EAGAIN;
        case 3:  return ECONNREFUSED;
        case 4:  return EADDRINUSE;
        case 5:  return EADDRNOTAVAIL;
        case 6:  return ETIMEDOUT;
        case 7:  return ENOTCONN;
        case 8:  return ECONNRESET;
        case 9:  return EPIPE;
        case 10: return EHOSTUNREACH;
        case 11: return ENETUNREACH;
        case 12: return EINVAL;
        case 14: return EACCES;
        case 15: return EISCONN;
        case 16: return EOPNOTSUPP;
        default: return EIO;
    }
}

static int net_result(int rc)
{
    if (rc >= 0)
    {
        return rc;
    }
    errno = net_errno(-rc);
    return -1;
}

/* the handle of socket descriptor s, or -1 with errno EBADF */
static int handle(int s)
{
    int h = s - ORX_WASI_SOCKET_BASE;
    if (h <= 0 || h >= MAX_SOCKETS || !sockets[h].used)
    {
        errno = EBADF;
        return -1;
    }
    return h;
}

static int is_socket(int s)
{
    int h = s - ORX_WASI_SOCKET_BASE;
    return h > 0 && h < MAX_SOCKETS && sockets[h].used;
}

/* the launcher's wait for a blocking call: 0 do not wait, -1 forever */
static int wait_ms(int h, int sending)
{
    if (sockets[h].nonblocking)
    {
        return 0;
    }
    int t = sending ? sockets[h].sndtimeo : sockets[h].rcvtimeo;
    return t > 0 ? t : -1;
}

static int sin_addr_of(const struct sockaddr *a, socklen_t l, int *addr, int *port)
{
    if (a == NULL || l < (socklen_t)sizeof(struct sockaddr_in) || a->sa_family != AF_INET)
    {
        errno = a != NULL && a->sa_family != AF_INET ? EAFNOSUPPORT : EINVAL;
        return -1;
    }
    const struct sockaddr_in *in = (const struct sockaddr_in *)a;
    *addr = (int)ntohl(in->sin_addr.s_addr);
    *port = ntohs(in->sin_port);
    return 0;
}

static void put_sin(struct sockaddr *a, socklen_t *l, const int32_t *p)
{
    if (a == NULL || l == NULL)
    {
        return;
    }
    struct sockaddr_in in;
    memset(&in, 0, sizeof(in));
    in.sin_family = AF_INET;
    in.sin_addr.s_addr = htonl((uint32_t)p[0]);
    in.sin_port = htons((uint16_t)p[1]);
    socklen_t n = *l < (socklen_t)sizeof(in) ? *l : (socklen_t)sizeof(in);
    memcpy(a, &in, n);
    *l = sizeof(in);
}

int orx_wasi_socket(int domain, int type, int protocol)
{
    (void)protocol;
    if (domain != AF_INET)
    {
        errno = EAFNOSUPPORT;
        return -1;
    }
    type &= ~(SOCK_NONBLOCK | SOCK_CLOEXEC);
    if (type != SOCK_STREAM && type != SOCK_DGRAM)
    {
        errno = EPROTONOSUPPORT;
        return -1;
    }
    int h = net_result(rxj_net_socket(type == SOCK_STREAM ? 1 : 0));
    if (h < 0)
    {
        return -1;
    }
    if (h >= MAX_SOCKETS)
    {
        rxj_net_close(h);
        errno = EMFILE;
        return -1;
    }
    pthread_mutex_lock(&lock);
    memset(&sockets[h], 0, sizeof(sockets[h]));
    sockets[h].used = 1;
    sockets[h].type = type;
    pthread_mutex_unlock(&lock);
    return ORX_WASI_SOCKET_BASE + h;
}

int orx_wasi_bind(int s, const struct sockaddr *a, socklen_t l)
{
    int h = handle(s), addr, port;
    if (h < 0 || sin_addr_of(a, l, &addr, &port) < 0)
    {
        return -1;
    }
    return net_result(rxj_net_bind(h, addr, port));
}

int orx_wasi_listen(int s, int backlog)
{
    int h = handle(s);
    return h < 0 ? -1 : net_result(rxj_net_listen(h, backlog));
}

int orx_wasi_connect(int s, const struct sockaddr *a, socklen_t l)
{
    int h = handle(s), addr, port;
    if (h < 0 || sin_addr_of(a, l, &addr, &port) < 0)
    {
        return -1;
    }
    int t = sockets[h].sndtimeo;
    return net_result(rxj_net_connect(h, addr, port, t > 0 ? t : 0));
}

int orx_wasi_accept(int s, struct sockaddr *a, socklen_t *l)
{
    int h = handle(s);
    if (h < 0)
    {
        return -1;
    }
    int32_t out[2] = { 0, 0 };
    int n = net_result(rxj_net_accept(h, wait_ms(h, 0), out));
    if (n < 0)
    {
        return -1;
    }
    if (n >= MAX_SOCKETS)
    {
        rxj_net_close(n);
        errno = EMFILE;
        return -1;
    }
    pthread_mutex_lock(&lock);
    memset(&sockets[n], 0, sizeof(sockets[n]));
    sockets[n].used = 1;
    sockets[n].type = SOCK_STREAM;
    pthread_mutex_unlock(&lock);
    put_sin(a, l, out);
    return ORX_WASI_SOCKET_BASE + n;
}

ssize_t orx_wasi_send(int s, const void *b, size_t n, int flags)
{
    int h = handle(s);
    (void)flags;
    return h < 0 ? -1 : net_result(rxj_net_send(h, b, (int)n, 0, wait_ms(h, 1)));
}

ssize_t orx_wasi_recv(int s, void *b, size_t n, int flags)
{
    int h = handle(s);
    if (h < 0)
    {
        return -1;
    }
    int t = (flags & MSG_DONTWAIT) ? 0 : wait_ms(h, 0);
    return net_result(rxj_net_recv(h, b, (int)n, (flags & MSG_PEEK) ? 1 : 0, t));
}

ssize_t orx_wasi_sendto(int s, const void *b, size_t n, int flags, const struct sockaddr *a, socklen_t l)
{
    int h = handle(s), addr, port;
    (void)flags;
    if (h < 0)
    {
        return -1;
    }
    if (a == NULL)
    {
        return orx_wasi_send(s, b, n, flags);
    }
    if (sin_addr_of(a, l, &addr, &port) < 0)
    {
        return -1;
    }
    return net_result(rxj_net_sendto(h, b, (int)n, addr, port, 0));
}

ssize_t orx_wasi_recvfrom(int s, void *b, size_t n, int flags, struct sockaddr *a, socklen_t *l)
{
    int h = handle(s);
    if (h < 0)
    {
        return -1;
    }
    int32_t out[2] = { 0, 0 };
    int t = (flags & MSG_DONTWAIT) ? 0 : wait_ms(h, 0);
    int r = net_result(rxj_net_recvfrom(h, b, (int)n, t, out));
    if (r >= 0)
    {
        put_sin(a, l, out);
    }
    return r;
}

int orx_wasi_shutdown(int s, int how)
{
    int h = handle(s);
    return h < 0 ? -1 : net_result(rxj_net_shutdown(h, how == SHUT_RD ? 0 : how == SHUT_WR ? 1 : 2));
}

int orx_wasi_sock_close(int s)
{
    if (!is_socket(s))
    {
        return close(s);
    }
    int h = s - ORX_WASI_SOCKET_BASE;
    pthread_mutex_lock(&lock);
    sockets[h].used = 0;
    pthread_mutex_unlock(&lock);
    return net_result(rxj_net_close(h));
}

static int name_of(int s, int peer, struct sockaddr *a, socklen_t *l)
{
    int h = handle(s);
    if (h < 0)
    {
        return -1;
    }
    int32_t out[2] = { 0, 0 };
    if (net_result(rxj_net_name(h, peer, out)) < 0)
    {
        return -1;
    }
    put_sin(a, l, out);
    return 0;
}

int orx_wasi_getsockname(int s, struct sockaddr *a, socklen_t *l) { return name_of(s, 0, a, l); }
int orx_wasi_getpeername(int s, struct sockaddr *a, socklen_t *l) { return name_of(s, 1, a, l); }

/* the code Net.java applies, or 0 for an option only remembered here */
static int java_option(int level, int name)
{
    if (level == IPPROTO_TCP)
    {
        return name == TCP_NODELAY ? O_NODELAY : 0;
    }
    switch (name)
    {
        case SO_REUSEADDR: return O_REUSEADDR;
        case SO_KEEPALIVE: return O_KEEPALIVE;
        case SO_RCVBUF:    return O_RCVBUF;
        case SO_SNDBUF:    return O_SNDBUF;
        case SO_BROADCAST: return O_BROADCAST;
        case SO_OOBINLINE: return O_OOBINLINE;
        default:           return 0;
    }
}

static int *option_slot(int h, int level, int name, int create)
{
    SocketInfo *si = &sockets[h];
    for (int i = 0; i < si->nopts; i++)
    {
        if (si->opts[i].level == level && si->opts[i].name == name)
        {
            return &si->opts[i].value;
        }
    }
    if (!create || si->nopts == MAX_OPTIONS)
    {
        return NULL;
    }
    si->opts[si->nopts].level = level;
    si->opts[si->nopts].name = name;
    si->opts[si->nopts].value = 0;
    return &si->opts[si->nopts++].value;
}

int orx_wasi_setsockopt(int s, int level, int name, const void *p, socklen_t l)
{
    int h = handle(s);
    if (h < 0)
    {
        return -1;
    }
    if (p == NULL)
    {
        errno = EFAULT;
        return -1;
    }
    if (level == SOL_SOCKET)
    {
        switch (name)
        {
            case SO_TYPE:
            case SO_ERROR:
                errno = ENOPROTOOPT;
                return -1;
            case SO_RCVTIMEO:
            case SO_SNDTIMEO:
            {
                if (l < (socklen_t)sizeof(struct timeval))
                {
                    errno = EINVAL;
                    return -1;
                }
                const struct timeval *tv = (const struct timeval *)p;
                int ms = (int)(tv->tv_sec * 1000 + tv->tv_usec / 1000);
                if (name == SO_RCVTIMEO) sockets[h].rcvtimeo = ms; else sockets[h].sndtimeo = ms;
                return 0;
            }
            case SO_LINGER:
            {
                if (l < (socklen_t)sizeof(struct linger))
                {
                    errno = EINVAL;
                    return -1;
                }
                sockets[h].linger = *(const struct linger *)p;
                return net_result(rxj_net_setopt(h, O_LINGER,
                    sockets[h].linger.l_onoff ? sockets[h].linger.l_linger : -1));
            }
            default:
                break;
        }
    }
    if (l < (socklen_t)sizeof(int))
    {
        errno = EINVAL;
        return -1;
    }
    int v = *(const int *)p;
    int *slot = option_slot(h, level, name, 1);
    if (slot == NULL)
    {
        errno = ENOPROTOOPT;
        return -1;
    }
    *slot = v;
    int j = java_option(level, name);
    return j == 0 ? 0 : net_result(rxj_net_setopt(h, j, v));
}

int orx_wasi_getsockopt(int s, int level, int name, void *p, socklen_t *l)
{
    int h = handle(s);
    if (h < 0)
    {
        return -1;
    }
    if (p == NULL || l == NULL)
    {
        errno = EFAULT;
        return -1;
    }
    if (level == SOL_SOCKET)
    {
        switch (name)
        {
            case SO_RCVTIMEO:
            case SO_SNDTIMEO:
            {
                if (*l < (socklen_t)sizeof(struct timeval))
                {
                    errno = EINVAL;
                    return -1;
                }
                int ms = name == SO_RCVTIMEO ? sockets[h].rcvtimeo : sockets[h].sndtimeo;
                struct timeval *tv = (struct timeval *)p;
                tv->tv_sec = ms / 1000;
                tv->tv_usec = (ms % 1000) * 1000;
                *l = sizeof(struct timeval);
                return 0;
            }
            case SO_LINGER:
                if (*l < (socklen_t)sizeof(struct linger))
                {
                    errno = EINVAL;
                    return -1;
                }
                *(struct linger *)p = sockets[h].linger;
                *l = sizeof(struct linger);
                return 0;
            default:
                break;
        }
    }
    if (*l < (socklen_t)sizeof(int))
    {
        errno = EINVAL;
        return -1;
    }
    int v = 0;
    if (level == SOL_SOCKET && name == SO_TYPE)
    {
        v = sockets[h].type;
    }
    else if (level == SOL_SOCKET && name == SO_ERROR)
    {
        v = 0;
    }
    else
    {
        int *slot = option_slot(h, level, name, 0);
        if (slot != NULL)
        {
            v = *slot;
        }
        else if (level == SOL_SOCKET && (name == SO_RCVBUF || name == SO_SNDBUF))
        {
            v = 65536;
        }
    }
    *(int *)p = v;
    *l = sizeof(int);
    return 0;
}

int orx_wasi_sock_ioctl(int s, int request, void *arg, ...)
{
    if (!is_socket(s))
    {
        return ioctl(s, request, arg);
    }
    int h = s - ORX_WASI_SOCKET_BASE;
    if (request == FIONBIO)
    {
        sockets[h].nonblocking = arg != NULL && *(int *)arg != 0;
        return 0;
    }
    if (request == FIONREAD)
    {
        int n = net_result(rxj_net_available(h));
        if (n < 0)
        {
            return -1;
        }
        *(int *)arg = n;
        return 0;
    }
    errno = EINVAL;
    return -1;
}

/* select on sockets only (rxsock's): the readable, writable lists; exceptional
   conditions are never reported */
int orx_wasi_select(int nfds, fd_set *r, fd_set *w, fd_set *e, struct timeval *tv)
{
    (void)nfds;
    int nr = r != NULL ? (int)r->__nfds : 0, nw = w != NULL ? (int)w->__nfds : 0;
    int32_t rh[FD_SETSIZE], wh[FD_SETSIZE];
    for (int i = 0; i < nr; i++)
    {
        int h = handle(r->__fds[i]);
        if (h < 0) return -1;
        rh[i] = h;
    }
    for (int i = 0; i < nw; i++)
    {
        int h = handle(w->__fds[i]);
        if (h < 0) return -1;
        wh[i] = h;
    }
    int t = tv == NULL ? -1 : (int)(tv->tv_sec * 1000 + tv->tv_usec / 1000);
    int n = net_result(rxj_net_select(rh, nr, wh, nw, t, 0));
    if (n < 0)
    {
        return -1;
    }
    /* keep only the ready ones in each set */
    fd_set ro, wo;
    FD_ZERO(&ro);
    FD_ZERO(&wo);
    for (int i = 0; i < nr; i++) if (rh[i]) FD_SET(r->__fds[i], &ro);
    for (int i = 0; i < nw; i++) if (wh[i]) FD_SET(w->__fds[i], &wo);
    if (r != NULL) *r = ro;
    if (w != NULL) *w = wo;
    if (e != NULL) FD_ZERO(e);
    return n;
}

/* name resolution, in static storage like the libc functions */
#define MAX_ADDRS 16
static char hostText[1024];
static char *hostAliases[1];
static struct in_addr hostAddrs[MAX_ADDRS];
static char *hostAddrList[MAX_ADDRS + 1];
static struct hostent hostEnt;

static struct hostent *host_from_text(void)
{
    int count = 0;
    char *next = strchr(hostText, '\n');
    while (next != NULL && count < MAX_ADDRS)
    {
        *next++ = '\0';
        char *addr = next;
        next = strchr(addr, '\n');
        if (next != NULL) *next = '\0';
        if (inet_aton(addr, &hostAddrs[count]))
        {
            hostAddrList[count] = (char *)&hostAddrs[count];
            count++;
        }
        if (next != NULL) *next = '\n';
    }
    if (count == 0)
    {
        return NULL;
    }
    hostAddrList[count] = NULL;
    hostAliases[0] = NULL;
    hostEnt.h_name = hostText;
    hostEnt.h_aliases = hostAliases;
    hostEnt.h_addrtype = AF_INET;
    hostEnt.h_length = sizeof(struct in_addr);
    hostEnt.h_addr_list = hostAddrList;
    return &hostEnt;
}

struct hostent *orx_wasi_gethostbyname(const char *name)
{
    if (name == NULL || rxj_net_resolve(1, name, hostText, sizeof(hostText)) < 0)
    {
        return NULL;
    }
    return host_from_text();
}

struct hostent *orx_wasi_gethostbyaddr(const void *a, socklen_t l, int type)
{
    if (a == NULL || type != AF_INET || l != sizeof(struct in_addr))
    {
        return NULL;
    }
    char text[INET_ADDRSTRLEN];
    if (inet_ntop(AF_INET, a, text, sizeof(text)) == NULL ||
        rxj_net_resolve(2, text, hostText, sizeof(hostText) - INET_ADDRSTRLEN - 2) < 0)
    {
        return NULL;
    }
    /* "name\naddress", as gethostbyname's text */
    strcat(hostText, "\n");
    strcat(hostText, text);
    return host_from_text();
}

int orx_wasi_gethostname(char *buf, size_t size)
{
    if (buf == NULL || size == 0)
    {
        errno = EINVAL;
        return -1;
    }
    char name[256];
    if (rxj_net_resolve(0, NULL, name, sizeof(name)) < 0)
    {
        strcpy(name, "localhost");
    }
    if (strlen(name) + 1 > size)
    {
        errno = ENAMETOOLONG;
        return -1;
    }
    strcpy(buf, name);
    return 0;
}
