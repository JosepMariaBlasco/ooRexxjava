/* WASI compatibility: no name resolution in WASI preview 1. */
#ifndef ORX_WASI_NETDB_H
#define ORX_WASI_NETDB_H
#ifdef __cplusplus
extern "C" {
#endif
struct hostent { char *h_name; char **h_aliases; int h_addrtype; int h_length; char **h_addr_list; };
#define h_addr h_addr_list[0]
static inline struct hostent *gethostbyname(const char *n) { (void)n; return 0; }
struct servent { char *s_name; char **s_aliases; int s_port; char *s_proto; };
/* orx_wasi_stubs.c: from /etc/services, which the host's file system has */
struct servent *getservbyname(const char *name, const char *proto);
struct servent *getservbyport(int port, const char *proto);
#ifdef __cplusplus
}
#endif
#endif
