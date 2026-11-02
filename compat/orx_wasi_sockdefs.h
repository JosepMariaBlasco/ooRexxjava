/* Socket constants wasi-libc defines only for WASI preview 2 and 3 (its
   __header_sys_socket.h): for rxsock and orx_wasi_net.c, Linux's values.
   SOL_SOCKET, SO_TYPE, SOCK_*, AF_*, SHUT_* and MSG_PEEK are wasi-libc's. */
#ifndef ORX_WASI_SOCKDEFS_H
#define ORX_WASI_SOCKDEFS_H
#include <sys/socket.h>
#ifndef SO_DEBUG
#define SO_DEBUG        1
#endif
#ifndef SO_REUSEADDR
#define SO_REUSEADDR    2
#define SO_ERROR        4
#define SO_SNDBUF       7
#define SO_RCVBUF       8
#define SO_KEEPALIVE    9
#endif
#ifndef SO_DONTROUTE
#define SO_DONTROUTE    5
#endif
#ifndef SO_BROADCAST
#define SO_BROADCAST    6
#endif
#ifndef SO_OOBINLINE
#define SO_OOBINLINE    10
#endif
#ifndef SO_LINGER
#define SO_LINGER       13
#endif
#ifndef SO_REUSEPORT
#define SO_REUSEPORT    15
#endif
#ifndef SO_RCVLOWAT
#define SO_RCVLOWAT     18
#define SO_SNDLOWAT     19
#endif
#ifndef SO_RCVTIMEO
#define SO_RCVTIMEO     66
#define SO_SNDTIMEO     67
#endif
#ifndef MSG_OOB
#define MSG_OOB         0x0001
#endif
#ifndef MSG_DONTROUTE
#define MSG_DONTROUTE   0x0004
#endif
#ifndef MSG_DONTWAIT
#define MSG_DONTWAIT    0x0040
#endif
#ifndef SOMAXCONN
#define SOMAXCONN       128
#endif
#ifndef SOCK_RAW
#define SOCK_RAW        3
#endif
/* errno values rxsock names (its table of error names) that WASI lacks:
   Linux's numbers, above WASI's (0 to 76) */
#include <errno.h>
#ifndef ESOCKTNOSUPPORT
#define ESOCKTNOSUPPORT 94
#endif
#ifndef EPFNOSUPPORT
#define EPFNOSUPPORT    96
#endif
#ifndef ESHUTDOWN
#define ESHUTDOWN       108
#endif
#ifndef ETOOMANYREFS
#define ETOOMANYREFS    109
#endif
#ifndef EHOSTDOWN
#define EHOSTDOWN       112
#endif
#ifndef IPPROTO_TCP
#define IPPROTO_TCP     6
#endif
#endif
