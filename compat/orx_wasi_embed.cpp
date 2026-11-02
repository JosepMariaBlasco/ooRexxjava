/* The embedded mode: the interpreter hosted by a Java program (rexxjvm.Rexx).

   rexx --orx-wasm-embedded starts the dispatcher thread and waits in the launcher
   (rxj_embed_main_wait) until Java closes the module. Every request from Java goes
   through the dispatcher, a wasm thread waiting in rxj_embed_next(): it calls
   pthread_create, and the new pthread's worker calls rxj_embed_run(id). What runs
   there is Java's: the launcher's thread-spawn hands the new pthread to the Java
   thread that made the request (a foreign entry: Rexx runs on the caller's own Java
   thread), or starts a Java thread for it (an interpreter instance's home thread).
   Either way, the Java side then calls the exports below on that thread's Instance,
   from inside rxj_embed_run.

   Every interpreter instance lives on a home thread of its own: the thread that
   creates an instance is its root activity, bound to that thread for the instance's
   whole life, so it cannot be a short-lived worker (whose pthread_t could be reused).
   The home thread creates the instance, waits in rxj_embed_home() until Java closes
   it, and terminates it. Foreign entries attach to the instance (AttachThread), do
   their work and detach.

   Strings cross as "string lists" in linear memory, allocated with malloc: an int32
   count, then for each string an int32 length (-1: no string) and its bytes. */

#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "oorexxapi.h"

#define RXJ_IMPORT(name) __attribute__((import_module("rexxjvm"), import_name(#name)))
#define RXJ_EXPORT(name) extern "C" __attribute__((export_name(#name)))

extern "C" {
RXJ_IMPORT(embed_main_wait) void rxj_embed_main_wait(void);
RXJ_IMPORT(embed_next)      int  rxj_embed_next(void);
RXJ_IMPORT(embed_run)       void rxj_embed_run(int id);
RXJ_IMPORT(embed_home)      void rxj_embed_home(int id, int instance);
}

/* linear memory has no guard page: every thread we create gets an explicit, generous
   stack (an overflow would otherwise corrupt memory silently) */
#define WORKER_STACK (8 << 20)

static void *worker(void *arg)
{
    rxj_embed_run((int)(intptr_t)arg);
    return NULL;
}

static void *dispatcher(void *unused)
{
    (void)unused;
    for (;;)
    {
        int id = rxj_embed_next();
        if (id < 0)
        {
            break;
        }
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setstacksize(&attr, WORKER_STACK);
        pthread_t t;
        int rc = pthread_create(&t, &attr, worker, (void *)(intptr_t)id);
        pthread_attr_destroy(&attr);
        if (rc != 0)
        {
            /* the request still has to be answered: run it here (the Java side
               sees a dispatcher thread, not its own, and reports the failure) */
            fprintf(stderr, "rexx embedded: pthread_create: %d\n", rc);
            rxj_embed_run(id);
            continue;
        }
        pthread_detach(t);
    }
    return NULL;
}

/* rexx --orx-wasm-embedded (rexx.cpp) */
extern "C" int orxWasiEmbeddedMain(void)
{
    pthread_t d;
    if (pthread_create(&d, NULL, dispatcher, NULL) != 0)
    {
        fprintf(stderr, "rexx embedded: no dispatcher thread\n");
        return 70;
    }
    rxj_embed_main_wait();
    pthread_join(d, NULL);
    return 0;
}

RXJ_EXPORT(orx_embed_alloc) void *orx_embed_alloc(int size)
{
    return malloc(size > 0 ? size : 1);
}

RXJ_EXPORT(orx_embed_free) void orx_embed_free(void *p)
{
    free(p);
}

/* On a home thread: the instance's whole life. */
RXJ_EXPORT(orx_embed_home) int orx_embed_home(int id)
{
    RexxInstance *instance;
    RexxThreadContext *tc;
    if (!RexxCreateInterpreter(&instance, &tc, NULL))
    {
        rxj_embed_home(id, 0);
        return 0;
    }
    rxj_embed_home(id, (int)(intptr_t)instance);   // returns when Java closes it
    instance->Terminate();
    return 1;
}

/* A string list being built. */
struct Out
{
    char *buf;
    size_t len, cap;
    int32_t count;
};

static void outBytes(Out *o, const void *p, size_t n)
{
    if (o->len + n > o->cap)
    {
        size_t cap = o->cap ? o->cap : 64;
        while (cap < o->len + n)
        {
            cap *= 2;
        }
        o->buf = (char *)realloc(o->buf, cap);
        o->cap = cap;
    }
    memcpy(o->buf + o->len, p, n);
    o->len += n;
}

static void outString(Out *o, const char *s, int32_t n)
{
    outBytes(o, &n, 4);
    if (n > 0)
    {
        outBytes(o, s, n);
    }
    o->count++;
    memcpy(o->buf, &o->count, 4);
}

static void outCString(Out *o, const char *s)
{
    outString(o, s, s == NULL ? -1 : (int32_t)strlen(s));
}

static void outRexxString(Out *o, RexxThreadContext *tc, RexxStringObject s)
{
    if (s == NULLOBJECT)
    {
        outString(o, NULL, -1);
    }
    else
    {
        outString(o, tc->StringData(s), (int32_t)tc->StringLength(s));
    }
}

static void outNumber(Out *o, wholenumber_t n)
{
    char b[32];
    snprintf(b, sizeof b, "%ld", (long)n);
    outCString(o, b);
}

static void outStart(Out *o)
{
    o->buf = NULL;
    o->len = o->cap = 0;
    o->count = 0;
    int32_t zero = 0;
    outBytes(o, &zero, 4);
}

/* Reads string i of a list (0-based); returns its length, -1 for none. */
static int32_t inString(const char *list, int i, const char **s)
{
    const char *p = list + 4;
    int32_t n;
    for (;;)
    {
        memcpy(&n, p, 4);
        p += 4;
        if (i-- == 0)
        {
            *s = p;
            return n;
        }
        if (n > 0)
        {
            p += n;
        }
    }
}

/* The result of a call, as a string list: "R" and the result; "N" (no result); or
   "C" and the condition's name, code, rc, error text, message, program and line. */
static char *result(RexxThreadContext *tc, RexxObjectPtr r)
{
    Out o;
    outStart(&o);
    if (tc->CheckCondition())
    {
        RexxDirectoryObject info = tc->GetConditionInfo();
        RexxCondition c;
        memset(&c, 0, sizeof c);
        tc->DecodeConditionInfo(info, &c);
        outCString(&o, "C");
        outRexxString(&o, tc, c.conditionName);
        outNumber(&o, c.code);
        outNumber(&o, c.rc);
        outRexxString(&o, tc, c.errortext);
        outRexxString(&o, tc, c.message);
        outRexxString(&o, tc, c.program);
        outNumber(&o, (wholenumber_t)c.position);
        tc->ClearCondition();
    }
    else if (r == NULLOBJECT)
    {
        outCString(&o, "N");
    }
    else
    {
        outCString(&o, "R");
        outRexxString(&o, tc, tc->ObjectToString(r));
    }
    return o.buf;
}

/* On a foreign entry's thread: runs a program (mode 0: the first string is its file
   name) or a piece of code (mode 1: the first string is the code) in an instance;
   the other strings are the arguments (a missing one, an omitted argument). Returns
   the result as a string list, which the caller frees. */
RXJ_EXPORT(orx_embed_run) char *orx_embed_run(int instance, int mode, const char *list)
{
    RexxInstance *inst = (RexxInstance *)(intptr_t)instance;
    RexxThreadContext *tc;
    if (inst == NULL || !inst->AttachThread(&tc))
    {
        Out o;
        outStart(&o);
        outCString(&o, "E");
        outCString(&o, "cannot attach to the interpreter instance");
        return o.buf;
    }
    int32_t count;
    memcpy(&count, list, 4);
    RexxArrayObject args = tc->NewArray(count > 1 ? count - 1 : 0);
    for (int i = 1; i < count; i++)
    {
        const char *s;
        int32_t n = inString(list, i, &s);
        if (n >= 0)
        {
            tc->ArrayPut(args, tc->NewString(s, n), i);
        }
    }
    const char *what;
    int32_t whatLen = inString(list, 0, &what);
    RexxObjectPtr r = NULLOBJECT;
    if (mode == 0)
    {
        char *name = (char *)malloc(whatLen + 1);
        memcpy(name, what, whatLen);
        name[whatLen] = '\0';
        r = tc->CallProgram(name, args);
        free(name);
    }
    else
    {
        RexxRoutineObject routine = tc->NewRoutine("EMBEDDED", what, whatLen);
        if (routine != NULLOBJECT)
        {
            r = tc->CallRoutine(routine, args);
        }
    }
    char *out = result(tc, r);
    tc->DetachThread();
    return out;
}
