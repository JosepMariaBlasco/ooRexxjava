/*
 * vm.c: a middle-sized benchmark between MemBench's vm and rexxcps (README.md).
 *
 * A small interpreter with the shape of ooRexx's once compiled to WebAssembly: a
 * bytecode loop with a switch; values are heap objects (malloc/free, reference
 * counts) whose methods are reached through a table of function pointers per class
 * (call_indirect in the module); variables live in a hash table keyed by their
 * names, hashed and compared as strings; a frame stack in the linear memory; some
 * string building. All of it is loads and stores of the linear memory.
 *
 *   wasm32-wasip1-threads, memory imported (as rexx.wasm): build.sh
 *   rexxj vm.wasm [SECONDS] [ROUNDS]
 *
 * Prints, per round, the operations (bytecodes) per second, and a checksum that must
 * be the same for every memory.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ---- objects ---- */

typedef struct Obj Obj;
typedef struct Class {
    const char *name;
    Obj *(*add)(Obj *, Obj *);
    Obj *(*mul)(Obj *, Obj *);
    int (*less)(Obj *, Obj *);
    int64_t (*value)(Obj *);
    Obj *(*concat)(Obj *, Obj *);
} Class;

struct Obj {
    const Class *cls;
    int refs;
    int len;              /* Str: its length */
    int64_t num;          /* Int: its value */
    char text[];          /* Str: its characters */
};

static const Class IntClass, StrClass;
static long live;

static Obj *new_int(int64_t v) {
    Obj *o = malloc(sizeof(Obj));
    o->cls = &IntClass; o->refs = 1; o->len = 0; o->num = v;
    live++;
    return o;
}

static Obj *new_str(const char *s, int n) {
    Obj *o = malloc(sizeof(Obj) + n + 1);
    o->cls = &StrClass; o->refs = 1; o->len = n; o->num = 0;
    memcpy(o->text, s, n); o->text[n] = 0;
    live++;
    return o;
}

static inline Obj *ref(Obj *o) { o->refs++; return o; }
static inline void unref(Obj *o) { if (--o->refs == 0) { free(o); live--; } }

/* a Str's number: its digits, as Rexx reads a string as a number */
static int64_t str_value(Obj *o) {
    int64_t v = 0; int i = 0, neg = 0;
    if (o->len > 0 && o->text[0] == '-') { neg = 1; i = 1; }
    for (; i < o->len; i++) v = v * 10 + (o->text[i] - '0');
    return neg ? -v : v;
}
static int64_t int_value(Obj *o) { return o->num; }

static Obj *num_add(Obj *a, Obj *b) { return new_int(a->cls->value(a) + b->cls->value(b)); }
static Obj *num_mul(Obj *a, Obj *b) { return new_int((a->cls->value(a) * b->cls->value(b)) % 1000003); }
static int num_less(Obj *a, Obj *b) { return a->cls->value(a) < b->cls->value(b); }

static Obj *to_str(Obj *o) {
    if (o->cls == &StrClass) return ref(o);
    char buf[24]; int n = snprintf(buf, sizeof buf, "%lld", (long long)o->num);
    return new_str(buf, n);
}
static Obj *any_concat(Obj *a, Obj *b) {
    Obj *x = to_str(a), *y = to_str(b);
    int n = x->len + y->len; if (n > 40) n = 40;          /* keep strings short */
    char buf[64];
    memcpy(buf, x->text, x->len > 40 ? 40 : x->len);
    if (x->len < 40) memcpy(buf + x->len, y->text, n - x->len);
    Obj *r = new_str(buf, n);
    unref(x); unref(y);
    return r;
}

static const Class IntClass = { "Int", num_add, num_mul, num_less, int_value, any_concat };
static const Class StrClass = { "Str", num_add, num_mul, num_less, str_value, any_concat };

/* ---- variables: a hash table keyed by name ---- */

typedef struct Var { char name[16]; Obj *value; struct Var *next; } Var;
#define BUCKETS 64
typedef struct Frame { Var *bucket[BUCKETS]; Obj *stack[32]; int sp; } Frame;

static unsigned hash(const char *s) { unsigned h = 2166136261u; while (*s) h = (h ^ (unsigned char)*s++) * 16777619u; return h; }

static Var *lookup(Frame *f, const char *name) {
    unsigned b = hash(name) % BUCKETS;
    for (Var *v = f->bucket[b]; v; v = v->next) if (strcmp(v->name, name) == 0) return v;
    Var *v = calloc(1, sizeof(Var));
    strncpy(v->name, name, sizeof v->name - 1);
    v->value = new_int(0);
    v->next = f->bucket[b]; f->bucket[b] = v;
    return v;
}

/* ---- the bytecode ---- */

enum { PUSHI, PUSHS, LOAD, STORE, ADD, MUL, LESS, CONCAT, JUMP, JFALSE, POP, HALT };
typedef struct { int op; int64_t arg; const char *name; } Ins;

/* i = 0; s = 0; t = ""; do while i < N: s = s + i * i; if i < 50 then t = t || i; i = i + 1; end */
static Ins program[] = {
    /* 0 */ { PUSHI, 0, 0 }, { STORE, 0, "i" },
    /* 2 */ { PUSHI, 0, 0 }, { STORE, 0, "sum" },
    /* 4 */ { PUSHS, 0, "" }, { STORE, 0, "text" },
    /* 6 */ { LOAD, 0, "i" }, { PUSHI, 1000, 0 }, { LESS, 0, 0 }, { JFALSE, 31, 0 },
    /* 10 */ { LOAD, 0, "sum" }, { LOAD, 0, "i" }, { LOAD, 0, "i" }, { MUL, 0, 0 }, { ADD, 0, 0 }, { STORE, 0, "sum" },
    /* 16 */ { LOAD, 0, "i" }, { PUSHS, 0, "50" }, { LESS, 0, 0 }, { JFALSE, 25, 0 },
    /* 20 */ { LOAD, 0, "text" }, { LOAD, 0, "i" }, { CONCAT, 0, 0 }, { STORE, 0, "text" }, { JUMP, 25, 0 },
    /* 25 */ { LOAD, 0, "i" }, { PUSHI, 1, 0 }, { ADD, 0, 0 }, { STORE, 0, "i" }, { JUMP, 6, 0 }, { POP, 0, 0 },
    /* 31 */ { HALT, 0, 0 },
};

static long ops;

static int64_t run(void) {
    Frame *f = calloc(1, sizeof(Frame));
    for (int pc = 0;;) {
        Ins *in = &program[pc++];
        ops++;
        switch (in->op) {
        case PUSHI: f->stack[f->sp++] = new_int(in->arg); break;
        case PUSHS: f->stack[f->sp++] = new_str(in->name, (int)strlen(in->name)); break;
        case LOAD:  f->stack[f->sp++] = ref(lookup(f, in->name)->value); break;
        case STORE: { Var *v = lookup(f, in->name); unref(v->value); v->value = f->stack[--f->sp]; break; }
        case ADD: case MUL: case CONCAT: {
            Obj *b = f->stack[--f->sp], *a = f->stack[--f->sp];
            Obj *r = in->op == ADD ? a->cls->add(a, b) : in->op == MUL ? a->cls->mul(a, b) : a->cls->concat(a, b);
            unref(a); unref(b);
            f->stack[f->sp++] = r;
            break;
        }
        case LESS: {
            Obj *b = f->stack[--f->sp], *a = f->stack[--f->sp];
            Obj *r = new_int(a->cls->less(a, b));
            unref(a); unref(b);
            f->stack[f->sp++] = r;
            break;
        }
        case JUMP: pc = (int)in->arg; break;
        case JFALSE: { Obj *c = f->stack[--f->sp]; int64_t v = c->cls->value(c); unref(c); if (!v) pc = (int)in->arg; break; }
        case POP: unref(f->stack[--f->sp]); break;
        case HALT: {
            int64_t r = lookup(f, "sum")->value->num * 31 + lookup(f, "text")->value->len;
            for (int b = 0; b < BUCKETS; b++)
                for (Var *v = f->bucket[b], *n; v; v = n) { n = v->next; unref(v->value); free(v); }
            free(f);
            return r;
        }
        }
    }
}

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
    double seconds = argc > 1 ? atof(argv[1]) : 1;
    int rounds = argc > 2 ? atoi(argv[2]) : 8;
    int64_t check = run();
    if (live != 0) { printf("leak: %ld objects\n", live); return 1; }
    for (double end = now() + 3; now() < end; ) run();          /* warm-up */
    for (int r = 0; r < rounds; r++) {
        ops = 0;
        double t0 = now(), t;
        int64_t c;
        do { c = run(); t = now(); } while (t - t0 < seconds);
        if (c != check) { printf("checksum %lld, expected %lld\n", (long long)c, (long long)check); return 1; }
        printf("%.2f M ops/s\n", ops / (t - t0) / 1e6);
    }
    printf("checksum %lld\n", (long long)check);
    return 0;
}
