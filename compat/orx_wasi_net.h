/*
 * IPv4 sockets for rxsock under WASI preview 1, which has none: the calls
 * rxsock makes are redirected to orx_wasi_net.c, which does them through the
 * Java launcher (host/.../Net.java, java.nio). Included (-include) only in
 * rxsock's two sources (the WASI patch's CMakeLists.txt), after
 * orx_wasi_compat.h: close() and ioctl() here mean a socket's.
 *
 * A socket is a descriptor ORX_WASI_SOCKET_BASE + the launcher's handle, far
 * from WASI's own descriptors; wasi-libc's fd_set is a list of descriptors,
 * not a bit map, so FD_SET takes such numbers.
 *
 * The system headers come first, so that their declarations keep their names
 * and only the calls after this point are redirected.
 */
#ifndef ORX_WASI_NET_H
#define ORX_WASI_NET_H
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
#include <unistd.h>

#define ORX_WASI_SOCKET_BASE 0x10000

#ifdef __cplusplus
extern "C" {
#endif
int orx_wasi_socket(int, int, int);
int orx_wasi_bind(int, const struct sockaddr *, socklen_t);
int orx_wasi_listen(int, int);
int orx_wasi_connect(int, const struct sockaddr *, socklen_t);
int orx_wasi_accept(int, struct sockaddr *, socklen_t *);
ssize_t orx_wasi_send(int, const void *, size_t, int);
ssize_t orx_wasi_recv(int, void *, size_t, int);
ssize_t orx_wasi_sendto(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
ssize_t orx_wasi_recvfrom(int, void *, size_t, int, struct sockaddr *, socklen_t *);
int orx_wasi_shutdown(int, int);
int orx_wasi_sock_close(int);
int orx_wasi_getsockname(int, struct sockaddr *, socklen_t *);
int orx_wasi_getpeername(int, struct sockaddr *, socklen_t *);
int orx_wasi_setsockopt(int, int, int, const void *, socklen_t);
int orx_wasi_getsockopt(int, int, int, void *, socklen_t *);
int orx_wasi_sock_ioctl(int, int, void *, ...);
int orx_wasi_select(int, fd_set *, fd_set *, fd_set *, struct timeval *);
struct hostent *orx_wasi_gethostbyname(const char *);
struct hostent *orx_wasi_gethostbyaddr(const void *, socklen_t, int);
int orx_wasi_gethostname(char *, size_t);
#ifdef __cplusplus
}
#endif

#define socket(d, t, p)               orx_wasi_socket(d, t, p)
#define bind(s, a, l)                 orx_wasi_bind(s, a, l)
#define listen(s, b)                  orx_wasi_listen(s, b)
#define connect(s, a, l)              orx_wasi_connect(s, a, l)
#define accept(s, a, l)               orx_wasi_accept(s, a, l)
#define send(s, b, n, f)              orx_wasi_send(s, b, n, f)
#define recv(s, b, n, f)              orx_wasi_recv(s, b, n, f)
#define sendto(s, b, n, f, a, l)      orx_wasi_sendto(s, b, n, f, a, l)
#define recvfrom(s, b, n, f, a, l)    orx_wasi_recvfrom(s, b, n, f, a, l)
#define shutdown(s, h)                orx_wasi_shutdown(s, h)
#define close(s)                      orx_wasi_sock_close(s)
#define getsockname(s, a, l)          orx_wasi_getsockname(s, a, l)
#define getpeername(s, a, l)          orx_wasi_getpeername(s, a, l)
#define setsockopt(s, v, o, p, l)     orx_wasi_setsockopt(s, v, o, p, l)
#define getsockopt(s, v, o, p, l)     orx_wasi_getsockopt(s, v, o, p, l)
#define ioctl(s, r, ...)              orx_wasi_sock_ioctl(s, r, __VA_ARGS__)
#define select(n, r, w, e, t)         orx_wasi_select(n, r, w, e, t)
#define gethostbyname(n)              orx_wasi_gethostbyname(n)
#define gethostbyaddr(a, l, t)        orx_wasi_gethostbyaddr(a, l, t)
#define gethostname(b, l)             orx_wasi_gethostname(b, l)
#endif
