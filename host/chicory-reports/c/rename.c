/* rename() of a regular file inside a preopened directory. */
#include <errno.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    FILE *f = fopen("/old.txt", "w");
    if (!f) { printf("fopen: %s\n", strerror(errno)); return 1; }
    fputs("x\n", f);
    fclose(f);
    if (rename("/old.txt", "/new.txt") == 0) {
        printf("rename: ok\n");
        return 0;
    }
    printf("rename: %s (errno %d)\n", strerror(errno), errno);
    return 1;
}
