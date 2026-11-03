# Generates indirect.c: N functions of one signature, called through a table.
import sys
n = int(sys.argv[1])
out = ["/* Generated: %d functions of type int(int), called through function pointers. */" % n,
       "#include <stdio.h>", "#include <stdlib.h>", "#include <time.h>", ""]
for i in range(n):
    out.append("__attribute__((noinline)) int f%d(int x) { return x * %d + %d; }" % (i, 2 * i + 3, i))
out.append("int (*const table[%d])(int) = {%s};" % (n, ", ".join("f%d" % i for i in range(n))))
out.append(r'''
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }
int main(int argc, char **argv) {
    long iters = argc > 1 ? atol(argv[1]) : 20000000;
    unsigned s = 0;
    for (int round = 0; round < 5; round++) {
        double t0 = now();
        for (long i = 0; i < iters; i++) s += table[(i * 7919) % %N%](i);
        printf("round %d: %.0f ns per indirect call\n", round, (now() - t0) * 1e9 / iters);
    }
    return s == 42;
}'''.replace("%N%", str(n)))
open("c/indirect.c", "w").write("\n".join(out) + "\n")
