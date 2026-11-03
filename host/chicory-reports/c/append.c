/* O_RDWR | O_APPEND: writes go to the end, reads must still work. */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(void) {
    int fd = open("/log.txt", O_RDWR | O_APPEND | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) {
        printf("open O_TRUNC|O_APPEND: %s (errno %d)\n", strerror(errno), errno);
        fd = open("/log.txt", O_RDWR | O_APPEND | O_CREAT, 0666);
        if (fd < 0) { printf("open: %s\n", strerror(errno)); return 1; }
    }
    ssize_t w = write(fd, "abc\n", 4);
    printf("write: %zd\n", w);
    lseek(fd, 0, SEEK_SET);
    char buf[16];
    ssize_t r = read(fd, buf, sizeof buf);
    if (r >= 0) printf("read: %zd bytes\n", r);
    else printf("read: %s (errno %d)\n", strerror(errno), errno);
    close(fd);

    struct stat;
    int e = open("", O_RDONLY);
    if (e >= 0) printf("open(\"\"): succeeded (fd %d), expected ENOENT\n", e);
    else printf("open(\"\"): %s (errno %d)\n", strerror(errno), errno);
    return 0;
}
