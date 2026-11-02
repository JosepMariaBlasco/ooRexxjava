/* Prototype: can Java re-enter the module, and can a foreign Java thread run wasm code?
 * H1: Rexx -> Java -> Rexx on the same thread (an export called from inside a host function).
 * H2: a Java thread that is not a wasm thread enters the module: a dispatcher wasm thread
 *     calls pthread_create, and the launcher's thread-spawn hands (tid, startArg) to the
 *     foreign thread, which runs wasi_thread_start itself. */
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define IMPORT(name) __attribute__((import_module("proto"), import_name(#name)))
IMPORT(java_call)     int  java_call(int depth, int n);  /* Java calls export reenter(depth, n) */
IMPORT(dispatch_wait) int  dispatch_wait(void);          /* next foreign entry request; <0: stop */
IMPORT(entry_run)     void entry_run(int req);           /* the foreign thread's Java work */
IMPORT(main_wait)     void main_wait(void);              /* Java runs the foreign-thread tests */

static __thread int tls_counter;
#define WORKER_STACK (8 << 20)

__attribute__((export_name("reenter")))
int reenter(int depth, int n) {
    char buf[512];                      /* stack in linear memory */
    snprintf(buf, sizeof buf, "depth %d", depth);
    char *p = malloc(4096);             /* heap, shared and locked */
    memset(p, depth, 4096);
    tls_counter++;                      /* TLS */
    int r = depth > 0 ? java_call(depth - 1, n + 1) : n;
    if (strncmp(buf, "depth ", 6) != 0 || p[4095] != (char) depth) r = -1000000;  /* clobbered */
    free(p);
    return r;
}

__attribute__((export_name("tls_get")))   int tls_get(void)   { return tls_counter; }
__attribute__((export_name("self_id")))   int self_id(void)   { return (int)(intptr_t) pthread_self(); }

static void *worker(void *arg) {
    tls_counter = 0;
    entry_run((int)(intptr_t) arg);
    return NULL;
}

static void *dispatcher(void *unused) {
    (void) unused;
    for (;;) {
        int req = dispatch_wait();
        if (req < 0) break;
        pthread_t t;
        pthread_attr_t attr;            /* wasm has no guard page: an explicit, generous stack */
        pthread_attr_init(&attr);
        pthread_attr_setstacksize(&attr, WORKER_STACK);
        int rc = pthread_create(&t, &attr, worker, (void *)(intptr_t) req);
        pthread_attr_destroy(&attr);
        if (rc != 0) { fprintf(stderr, "pthread_create: %d\n", rc); continue; }
        pthread_detach(t);
    }
    return NULL;
}

int main(void) {
    tls_counter = 0;
    int r = java_call(10, 0);
    printf("H1 main thread: reenter chain returned %d (expected 10), tls %d (expected 11)\n", r, tls_counter);
    pthread_t d;
    if (pthread_create(&d, NULL, dispatcher, NULL) != 0) { puts("no dispatcher"); return 1; }
    main_wait();
    pthread_join(d, NULL);
    puts("main: done");
    return 0;
}
