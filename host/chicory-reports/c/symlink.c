/* Symbolic links with relative targets. Before running, on the host, in the
   directory preopened as "/" (symlink-setup.sh does it):
     mkdir sub && echo hello > sub/target.txt
     ln -s ../sub/target.txt sub/deeplink   (two components)
*/
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void show_stat(const char *p) {
    struct stat st;
    if (stat(p, &st) == 0) printf("  stat(%s): ok, size %lld\n", p, (long long)st.st_size);
    else printf("  stat(%s): %s (errno %d)\n", p, strerror(errno), errno);
}

static void show_readlink(const char *p, const char *expected) {
    char buf[256];
    ssize_t n = readlink(p, buf, sizeof buf - 1);
    if (n < 0) { printf("  readlink(%s): %s\n", p, strerror(errno)); return; }
    buf[n] = 0;
    printf("  readlink(%s): \"%s\" (expected \"%s\")\n", p, buf, expected);
}

int main(void) {
    printf("1. readlink of a two-component target:\n");
    show_readlink("/sub/deeplink", "../sub/target.txt");
    printf("2. link made through WASI path_symlink, relative target:\n");
    unlink("/sub/wasilink");
    if (symlink("target.txt", "/sub/wasilink") != 0) printf("  symlink: %s\n", strerror(errno));
    show_readlink("/sub/wasilink", "target.txt");
    show_stat("/sub/wasilink");
    return 0;
}
