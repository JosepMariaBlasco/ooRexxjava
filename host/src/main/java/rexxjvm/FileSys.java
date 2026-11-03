package rexxjvm;

import run.endive.runtime.HostFunction;
import run.endive.runtime.Memory;
import run.endive.wasm.types.ValType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * File system calls that WASI preview 1 cannot express, or that Chicory's WASI gets
 * wrong, for {@code compat/orx_wasi_fs.c} (which says why each one is here): stat and
 * lstat with mode, owner, group and signed times; access against the file's
 * permissions; chmod; rename; setting the times. Paths are the host's, absolute.
 * Every function returns 0, or one of the error codes below (orx_wasi_fs.c maps them
 * to the module's errno values).
 */
final class FileSys {
    static final int ENOENT = 1, EACCES = 2, EEXIST = 3, ENOTDIR = 4, EISDIR = 5, ENOTEMPTY = 6,
        EXDEV = 7, ELOOP = 8, EPERM = 9, EROFS = 10, EINVAL = 11, ENAMETOOLONG = 12, EBUSY = 13,
        ENOTSUP = 14, EIO = 15;

    /** orx_wasi_fs.c's file types (an index into its table of S_IFxxx) */
    static final int T_REG = 1, T_DIR = 2, T_LNK = 3, T_CHR = 4, T_BLK = 5, T_FIFO = 6, T_SOCK = 7;

    private static final LinkOption[] FOLLOW = {};
    private static final LinkOption[] NOFOLLOW = { LinkOption.NOFOLLOW_LINKS };

    /** Whether the "unix" attribute view works here (not on every JVM's host). */
    private static volatile boolean unixView = true;

    private FileSys() {}

    static List<HostFunction> functions() {
        List<ValType> i32 = List.of(ValType.I32);
        return List.of(
            new HostFunction("rexxjvm", "file_stat", List.of(ValType.I32, ValType.I32, ValType.I32), i32,
                (inst, a) -> new long[] { stat(inst.memory(), (int) a[0], a[1] != 0, (int) a[2]) }),
            new HostFunction("rexxjvm", "file_access", List.of(ValType.I32, ValType.I32), i32,
                (inst, a) -> new long[] { access(path(inst.memory(), (int) a[0]), (int) a[1]) }),
            new HostFunction("rexxjvm", "file_chmod", List.of(ValType.I32, ValType.I32), i32,
                (inst, a) -> new long[] { chmod(path(inst.memory(), (int) a[0]), (int) a[1]) }),
            new HostFunction("rexxjvm", "file_rename", List.of(ValType.I32, ValType.I32), i32,
                (inst, a) -> new long[] { rename(path(inst.memory(), (int) a[0]),
                                                 path(inst.memory(), (int) a[1])) }),
            new HostFunction("rexxjvm", "file_real_path", List.of(ValType.I32, ValType.I32, ValType.I32), i32,
                (inst, a) -> new long[] { realPath(inst.memory(), path(inst.memory(), (int) a[0]), (int) a[1], (int) a[2]) }),
            new HostFunction("rexxjvm", "file_symlink", List.of(ValType.I32, ValType.I32), i32,
                (inst, a) -> new long[] { symlink(inst.memory().readCString((int) a[0], StandardCharsets.UTF_8),
                                                  path(inst.memory(), (int) a[1])) }),
            new HostFunction("rexxjvm", "file_set_times",
                List.of(ValType.I32, ValType.I32, ValType.I64, ValType.I64), i32,
                (inst, a) -> new long[] { setTimes(path(inst.memory(), (int) a[0]), (int) a[1], a[2], a[3]) }));
    }

    /** The path at p, or null if the host cannot have it. */
    static Path path(Memory m, int p) {
        try {
            return Paths.get(m.readCString(p, StandardCharsets.UTF_8));
        } catch (InvalidPathException x) {
            return null;
        }
    }

    // orx_wasi_fs.c's out[]
    static final int F_TYPE = 0, F_MODE = 1, F_UID = 2, F_GID = 3, F_SIZE = 4, F_ATIME = 5, F_MTIME = 6,
        F_CTIME = 7, F_INO = 8, F_DEV = 9, F_NLINK = 10, F_RDEV = 11, F_COUNT = 12;

    static int stat(Memory m, int pathP, boolean follow, int out) {
        Path p = path(m, pathP);
        if (p == null) return ENOENT;
        long[] f = new long[F_COUNT];
        LinkOption[] lo = follow ? FOLLOW : NOFOLLOW;
        try {
            BasicFileAttributes b = Files.readAttributes(p, BasicFileAttributes.class, lo);
            f[F_TYPE] = b.isRegularFile() ? T_REG : b.isDirectory() ? T_DIR : b.isSymbolicLink() ? T_LNK : 0;
            f[F_SIZE] = b.size();
            f[F_ATIME] = nanos(b.lastAccessTime());
            f[F_MTIME] = nanos(b.lastModifiedTime());
            f[F_CTIME] = f[F_MTIME];
            f[F_NLINK] = 1;
            Map<String, Object> u = unixView ? unix(p, lo) : null;
            if (u != null) {
                int mode = ((Number) u.get("mode")).intValue();
                f[F_MODE] = mode & 07777;
                f[F_TYPE] = type(mode);
                f[F_UID] = ((Number) u.get("uid")).longValue();
                f[F_GID] = ((Number) u.get("gid")).longValue();
                f[F_INO] = ((Number) u.get("ino")).longValue();
                f[F_DEV] = ((Number) u.get("dev")).longValue();
                f[F_RDEV] = ((Number) u.get("rdev")).longValue();
                f[F_NLINK] = ((Number) u.get("nlink")).longValue();
                f[F_CTIME] = nanos((FileTime) u.get("ctime"));
            } else {
                f[F_MODE] = posixMode(p, lo, b);
            }
        } catch (IOException | RuntimeException x) {
            return error(x);
        }
        for (int i = 0; i < F_COUNT; i++) m.writeLong(out + 8 * i, f[i]);
        return 0;
    }

    private static Map<String, Object> unix(Path p, LinkOption[] lo) throws IOException {
        try {
            return Files.readAttributes(p, "unix:mode,uid,gid,ino,dev,rdev,nlink,ctime", lo);
        } catch (UnsupportedOperationException | IllegalArgumentException x) {
            unixView = false;
            return null;
        }
    }

    /** The type in an st_mode (S_IFMT), as orx_wasi_fs.c's index. */
    static int type(int mode) {
        switch (mode & 0170000) {
            case 0100000: return T_REG;
            case 0040000: return T_DIR;
            case 0120000: return T_LNK;
            case 0020000: return T_CHR;
            case 0060000: return T_BLK;
            case 0010000: return T_FIFO;
            case 0140000: return T_SOCK;
            default: return 0;
        }
    }

    /** Without the unix view: the POSIX permissions, if there are any; else what access says. */
    private static long posixMode(Path p, LinkOption[] lo, BasicFileAttributes b) {
        try {
            Set<PosixFilePermission> s = Files.getPosixFilePermissions(p, lo);
            long mode = 0;
            for (PosixFilePermission x : s) mode |= 0400 >> x.ordinal();   // OWNER_READ is 0400 ... OTHERS_EXECUTE 01
            return mode;
        } catch (UnsupportedOperationException | IOException x) {
            long mode = 0;
            if (Files.isReadable(p)) mode |= 0444;
            if (Files.isWritable(p)) mode |= 0200;
            if (b.isDirectory() || Files.isExecutable(p)) mode |= 0111;
            return mode;
        }
    }

    static long nanos(FileTime t) {
        return t == null ? 0 : t.to(TimeUnit.NANOSECONDS);
    }

    // access()'s modes (unistd.h): R_OK 4, W_OK 2, X_OK 1; 0 is F_OK
    static int access(Path p, int mode) {
        if (p == null) return ENOENT;
        List<AccessMode> l = new ArrayList<>();
        if ((mode & 4) != 0) l.add(AccessMode.READ);
        if ((mode & 2) != 0) l.add(AccessMode.WRITE);
        if ((mode & 1) != 0) l.add(AccessMode.EXECUTE);
        try {
            p.getFileSystem().provider().checkAccess(p, l.toArray(new AccessMode[0]));
            return 0;
        } catch (IOException | RuntimeException x) {
            return error(x);
        }
    }

    static int chmod(Path p, int mode) {
        if (p == null) return ENOENT;
        try {
            if (unixView) {
                try {
                    Files.setAttribute(p, "unix:mode", mode);
                    return 0;
                } catch (UnsupportedOperationException | IllegalArgumentException x) {
                    unixView = false;
                }
            }
            Set<PosixFilePermission> s = java.util.EnumSet.noneOf(PosixFilePermission.class);
            for (PosixFilePermission x : PosixFilePermission.values()) {
                if ((mode & (0400 >> x.ordinal())) != 0) s.add(x);
            }
            Files.setPosixFilePermissions(p, s);
            return 0;
        } catch (UnsupportedOperationException x) {
            // no POSIX permissions here: what DOS attributes can say, the write bit
            try {
                Files.setAttribute(p, "dos:readonly", (mode & 0222) == 0);
                return 0;
            } catch (IOException | RuntimeException y) {
                return ENOTSUP;
            }
        } catch (IOException | RuntimeException x) {
            return error(x);
        }
    }

    /** rename(2): an atomic move is exactly that on a POSIX host (it replaces the target). */
    static int rename(Path from, Path to) {
        if (from == null || to == null) return ENOENT;
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            return 0;
        } catch (IOException | RuntimeException x) {
            return error(x);
        }
    }

    /** The real path (symbolic links, "." and ".." resolved) of a directory, as chdir() sets it. */
    static int realPath(Memory m, Path p, int buf, int size) {
        if (p == null) return ENOENT;
        try {
            Path r = p.toRealPath();
            if (!Files.isDirectory(r)) return ENOTDIR;
            return Host.put(m, buf, size, r.toString()) == 1 ? 0 : ENAMETOOLONG;
        } catch (IOException | RuntimeException x) {
            return error(x);
        }
    }

    /** symlink(2): the target is stored as given, relative or absolute. */
    static int symlink(String target, Path link) {
        if (link == null) return ENOENT;
        try {
            Files.createSymbolicLink(link, Paths.get(target));
            return 0;
        } catch (InvalidPathException x) {
            return EINVAL;
        } catch (IOException | RuntimeException x) {
            return error(x);
        }
    }

    /** flags: 1 the access time, 2 the modification time; times in nanoseconds since 1970. */
    static int setTimes(Path p, int flags, long atime, long mtime) {
        if (p == null) return ENOENT;
        try {
            Files.getFileAttributeView(p, BasicFileAttributeView.class).setTimes(
                (flags & 2) != 0 ? FileTime.from(mtime, TimeUnit.NANOSECONDS) : null,
                (flags & 1) != 0 ? FileTime.from(atime, TimeUnit.NANOSECONDS) : null,
                null);
            return 0;
        } catch (IOException | RuntimeException x) {
            return error(x);
        }
    }

    /** The error code for what java.nio threw. */
    static int error(Throwable x) {
        if (x instanceof NoSuchFileException) return ENOENT;
        if (x instanceof AccessDeniedException) return EACCES;
        if (x instanceof FileAlreadyExistsException) return EEXIST;
        if (x instanceof DirectoryNotEmptyException) return ENOTEMPTY;
        if (x instanceof NotDirectoryException) return ENOTDIR;
        if (x instanceof AtomicMoveNotSupportedException) return EXDEV;
        if (x instanceof FileSystemLoopException) return ELOOP;
        if (x instanceof UnsupportedOperationException) return ENOTSUP;
        if (x instanceof FileSystemException) {
            // the JDK gives the other errors of the system call as its message (strerror)
            String r = ((FileSystemException) x).getReason();
            if (r == null) return EIO;
            switch (r) {
                case "Not a directory": return ENOTDIR;
                case "Is a directory": return EISDIR;
                case "Directory not empty": return ENOTEMPTY;
                case "Invalid cross-device link": return EXDEV;
                case "Too many levels of symbolic links": return ELOOP;
                case "Operation not permitted": return EPERM;
                case "Read-only file system": return EROFS;
                case "Invalid argument": return EINVAL;
                case "File name too long": return ENAMETOOLONG;
                case "Device or resource busy": return EBUSY;
                case "Permission denied": return EACCES;
                case "No such file or directory": return ENOENT;
                case "File exists": return EEXIST;
                default: return EIO;
            }
        }
        return EIO;
    }
}
