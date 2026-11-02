/*
 * File system calls that WASI preview 1 cannot express, or that Chicory's WASI
 * gets wrong, done by the Java launcher instead (import module "rexxjvm",
 * host/.../FileSys.java), over java.nio on the host's file system:
 *
 *   - stat/lstat: WASI's filestat has no mode, owner or group (st_mode came
 *     back with no permission bits), its times are unsigned (no date before
 *     1970), and Chicory 1.7.5 follows a relative symbolic link against the
 *     JVM's current directory instead of the link's (stat("/usr/bin/sh"),
 *     sh -> dash, failed).
 *   - fstat: no permission bits either (see orx_wasi_fstat).
 *   - access: WASI checks the capability rights of the preopened directory,
 *     not the file's permissions (every existing file was writable).
 *   - chmod: there is no chmod in WASI (setReadOnly did nothing).
 *   - rename: Chicory 1.7.5's path_rename asks Files.move for COPY_ATTRIBUTES,
 *     which the JDK refuses for a move: every rename failed with ENOTSUP.
 *   - chdir: wasi-libc keeps the current directory as the string it was
 *     given, joined to the old one ("cd x/.." left "/d/x/.."): it is set to
 *     the host's real path instead, as a POSIX getcwd() returns it.
 *   - symlink: Chicory 1.7.5 takes the target for a path to resolve, and
 *     refuses an absolute one (EACCES): the target is stored as given.
 *   - utimes/utime: as for stat, no time before 1970.
 *
 * Also, an empty path names nothing (ENOENT), as in POSIX; under WASI it was
 * the preopened directory itself.
 *
 * orx_wasi_compat.h redirects the interpreter's calls here (macros). This file
 * is compiled without that header. The launcher's file system is the host's
 * (preopened at "/"), so a path in the module is the host's path.
 */

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <utime.h>
#include <sys/stat.h>
#include <sys/time.h>

#define RXJ_IMPORT(name) __attribute__((import_module("rexxjvm"), import_name(#name)))

RXJ_IMPORT(file_stat)
int rxj_file_stat(const char *path, int follow, int64_t *out);
RXJ_IMPORT(file_access)
int rxj_file_access(const char *path, int mode);
RXJ_IMPORT(file_chmod)
int rxj_file_chmod(const char *path, int mode);
RXJ_IMPORT(file_rename)
int rxj_file_rename(const char *from, const char *to);
RXJ_IMPORT(file_real_path)
int rxj_file_real_path(const char *path, char *buf, int size);
RXJ_IMPORT(file_symlink)
int rxj_file_symlink(const char *target, const char *link);
RXJ_IMPORT(file_set_times)
int rxj_file_set_times(const char *path, int flags, int64_t atime, int64_t mtime);

/* FileSys.java's error codes, mapped to this libc's errno values */
static int rxj_errno(int code)
{
    switch (code)
    {
        case 1:  return ENOENT;
        case 2:  return EACCES;
        case 3:  return EEXIST;
        case 4:  return ENOTDIR;
        case 5:  return EISDIR;
        case 6:  return ENOTEMPTY;
        case 7:  return EXDEV;
        case 8:  return ELOOP;
        case 9:  return EPERM;
        case 10: return EROFS;
        case 11: return EINVAL;
        case 12: return ENAMETOOLONG;
        case 13: return EBUSY;
        case 14: return ENOTSUP;
        default: return EIO;
    }
}

static int rxj_result(int code)
{
    if (code == 0)
    {
        return 0;
    }
    errno = rxj_errno(code);
    return -1;
}

/* The absolute path of p (relative to WASI's current directory), in buf;
   NULL, with errno set, if there is none */
static const char *rxj_path(const char *p, char *buf, size_t size)
{
    if (p == NULL)
    {
        errno = EFAULT;
        return NULL;
    }
    if (*p == '\0')
    {
        errno = ENOENT;
        return NULL;
    }
    if (*p == '/')
    {
        return p;
    }
    if (getcwd(buf, size) == NULL)
    {
        return NULL;
    }
    size_t l = strlen(buf);
    if (l + 1 + strlen(p) + 1 > size)
    {
        errno = ENAMETOOLONG;
        return NULL;
    }
    if (l == 0 || buf[l - 1] != '/')
    {
        buf[l++] = '/';
    }
    strcpy(buf + l, p);
    return buf;
}

/* out[], as FileSys.fileStat writes it */
enum { F_TYPE, F_MODE, F_UID, F_GID, F_SIZE, F_ATIME, F_MTIME, F_CTIME, F_INO, F_DEV, F_NLINK, F_RDEV, F_COUNT };

static void rxj_timespec(struct timespec *ts, int64_t ns)
{
    int64_t s = ns / 1000000000;
    int64_t r = ns % 1000000000;
    if (r < 0)
    {
        s--;
        r += 1000000000;
    }
    ts->tv_sec = (time_t)s;
    ts->tv_nsec = (long)r;
}

static int rxj_stat(const char *p, struct stat *st, int follow)
{
    char buf[PATH_MAX];
    const char *path = rxj_path(p, buf, sizeof(buf));
    if (path == NULL)
    {
        return -1;
    }
    int64_t f[F_COUNT];
    int rc = rxj_file_stat(path, follow, f);
    if (rc != 0)
    {
        return rxj_result(rc);
    }
    static const mode_t types[] = { 0, S_IFREG, S_IFDIR, S_IFLNK, S_IFCHR, S_IFBLK, S_IFIFO, S_IFSOCK };
    memset(st, 0, sizeof(*st));
    st->st_mode = (f[F_TYPE] >= 0 && f[F_TYPE] < 8 ? types[f[F_TYPE]] : 0) | (mode_t)(f[F_MODE] & 07777);
    st->st_uid = (uid_t)f[F_UID];
    st->st_gid = (gid_t)f[F_GID];
    st->st_size = (off_t)f[F_SIZE];
    st->st_ino = (ino_t)f[F_INO];
    st->st_dev = (dev_t)f[F_DEV];
    st->st_rdev = (dev_t)f[F_RDEV];
    st->st_nlink = (nlink_t)f[F_NLINK];
    st->st_blksize = 4096;
    st->st_blocks = (blkcnt_t)((f[F_SIZE] + 511) / 512);
    rxj_timespec(&st->st_atim, f[F_ATIME]);
    rxj_timespec(&st->st_mtim, f[F_MTIME]);
    rxj_timespec(&st->st_ctim, f[F_CTIME]);
    return 0;
}

int orx_wasi_stat(const char *restrict p, struct stat *restrict st)
{
    return rxj_stat(p, st, 1);
}

int orx_wasi_lstat(const char *restrict p, struct stat *restrict st)
{
    return rxj_stat(p, st, 0);
}

/* fstat has a descriptor, not a path: WASI's fstat (type, size, times), with
   permission bits for what the descriptor was opened for (SysFile reads them
   to decide whether a stream is readable and writable) */
int orx_wasi_fstat(int fd, struct stat *st)
{
    int rc = fstat(fd, st);
    if (rc != 0 || (st->st_mode & 07777) != 0)
    {
        return rc;
    }
    if (S_ISDIR(st->st_mode))
    {
        st->st_mode |= 0755;
        return 0;
    }
    int fl = fcntl(fd, F_GETFL);
    if (fl == -1)
    {
        return 0;
    }
    switch (fl & O_ACCMODE)
    {
        case O_RDONLY: st->st_mode |= 0444; break;
        case O_WRONLY: st->st_mode |= 0200; break;
        default:       st->st_mode |= 0644; break;
    }
    return 0;
}

int orx_wasi_access(const char *p, int mode)
{
    char buf[PATH_MAX];
    const char *path = rxj_path(p, buf, sizeof(buf));
    return path == NULL ? -1 : rxj_result(rxj_file_access(path, mode & (R_OK | W_OK | X_OK)));
}

int orx_wasi_chmod(const char *p, mode_t mode)
{
    char buf[PATH_MAX];
    const char *path = rxj_path(p, buf, sizeof(buf));
    return path == NULL ? -1 : rxj_result(rxj_file_chmod(path, (int)(mode & 07777)));
}

int orx_wasi_rename(const char *from, const char *to)
{
    char b1[PATH_MAX], b2[PATH_MAX];
    const char *f = rxj_path(from, b1, sizeof(b1));
    if (f == NULL)
    {
        return -1;
    }
    const char *t = rxj_path(to, b2, sizeof(b2));
    return t == NULL ? -1 : rxj_result(rxj_file_rename(f, t));
}

/* flags: 1 set the access time, 2 set the modification time */
static int rxj_times(const char *p, int flags, int64_t atime, int64_t mtime)
{
    char buf[PATH_MAX];
    const char *path = rxj_path(p, buf, sizeof(buf));
    return path == NULL ? -1 : rxj_result(rxj_file_set_times(path, flags, atime, mtime));
}

static int64_t rxj_now(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec;
}

int orx_wasi_utimes(const char *p, const struct timeval tv[2])
{
    if (tv == NULL)
    {
        int64_t now = rxj_now();
        return rxj_times(p, 3, now, now);
    }
    return rxj_times(p, 3, (int64_t)tv[0].tv_sec * 1000000000 + (int64_t)tv[0].tv_usec * 1000,
                           (int64_t)tv[1].tv_sec * 1000000000 + (int64_t)tv[1].tv_usec * 1000);
}

int orx_wasi_utime(const char *p, const struct utimbuf *t)
{
    if (t == NULL)
    {
        int64_t now = rxj_now();
        return rxj_times(p, 3, now, now);
    }
    return rxj_times(p, 3, (int64_t)t->actime * 1000000000, (int64_t)t->modtime * 1000000000);
}

int orx_wasi_chdir(const char *p)
{
    char buf[PATH_MAX], real[PATH_MAX];
    const char *path = rxj_path(p, buf, sizeof(buf));
    if (path == NULL)
    {
        return -1;
    }
    int rc = rxj_file_real_path(path, real, sizeof(real));
    if (rc != 0)
    {
        return rxj_result(rc);
    }
    return chdir(real);
}

int orx_wasi_symlink(const char *target, const char *link)
{
    char buf[PATH_MAX];
    if (target == NULL || *target == '\0')
    {
        errno = ENOENT;
        return -1;
    }
    const char *path = rxj_path(link, buf, sizeof(buf));
    return path == NULL ? -1 : rxj_result(rxj_file_symlink(target, path));
}
