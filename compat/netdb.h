/* WASI compatibility: no name resolution in WASI preview 1. */
#ifndef ORX_WASI_NETDB_H
#define ORX_WASI_NETDB_H
#ifdef __cplusplus
extern "C" {
#endif
struct hostent { char *h_name; char **h_aliases; int h_addrtype; int h_length; char **h_addr_list; };
#define h_addr h_addr_list[0]
static inline struct hostent *gethostbyname(const char *n) { (void)n; return 0; }
#ifdef __cplusplus
}
#endif
#endif
