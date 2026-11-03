package rexxjvm;

import run.endive.runtime.HostFunction;
import run.endive.runtime.Memory;
import run.endive.wasi.WasiPreview1;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Endive's WASI on Windows: its fd_filestat_get, path_filestat_get and fd_readdir read
 * a file's attributes as {@code "unix:*"}, a view that Windows does not have, and give
 * ENOTSUP for every file (so the module could not even read rexx.img: its size comes
 * from fstat). Here, on Windows only, the three are replaced by versions on
 * {@link BasicFileAttributes}, with what Windows has: no inode numbers (a hash of the
 * real path stands for them), one link, ctime as the modification time. Endive's
 * descriptors (which path an fd is) are read by reflection; a descriptor with no path
 * (the standard streams) goes to Endive's own function, which handles it without the
 * unix view. Reported: bytecodealliance/endive#280; drop this when a release fixes it.
 */
final class WinWasi {
    // WASI's errno values and file types (wasi_snapshot_preview1)
    static final int ESUCCESS = 0, EACCES = 2, EBADF = 8, EINVAL = 28, EIO = 29, ENOENT = 44,
        ENOTDIR = 54, ENOTCAPABLE = 76;
    static final int T_UNKNOWN = 0, T_DIR = 3, T_REG = 4, T_LNK = 7;
    static final int SYMLINK_FOLLOW = 1;

    private final WasiPreview1 wasi;
    private final Field descriptors;
    private final Method get;

    private WinWasi(WasiPreview1 wasi) throws ReflectiveOperationException {
        this.wasi = wasi;
        descriptors = WasiPreview1.class.getDeclaredField("descriptors");
        descriptors.setAccessible(true);
        get = descriptors.getType().getDeclaredMethod("get", int.class);
        get.setAccessible(true);
    }

    /** WASI's host functions, with the three replaced on Windows; as they are elsewhere. */
    static HostFunction[] functions(WasiPreview1 wasi) {
        HostFunction[] fs = wasi.toHostFunctions();
        if (!WinPath.WINDOWS) return fs;
        WinWasi w;
        try {
            w = new WinWasi(wasi);
        } catch (ReflectiveOperationException | RuntimeException x) {
            return fs;                            // another Endive: leave its own
        }
        for (int i = 0; i < fs.length; i++) {
            HostFunction f = fs[i];
            switch (f.name()) {
                case "fd_filestat_get":
                    fs[i] = new HostFunction(f.module(), f.name(), f.paramTypes(), f.returnTypes(), (inst, a) -> {
                        Path p = w.path((int) a[0]);
                        if (p == null) return f.handle().apply(inst, a);
                        return new long[] { stat(inst.memory(), p, true, (int) a[1]) };
                    });
                    break;
                case "path_filestat_get":
                    fs[i] = new HostFunction(f.module(), f.name(), f.paramTypes(), f.returnTypes(), (inst, a) -> {
                        Path dir = w.path((int) a[0]);
                        if (dir == null) return new long[] { EBADF };
                        String name = new String(inst.memory().readBytes((int) a[2], (int) a[3]), StandardCharsets.UTF_8);
                        Path p = resolve(dir, name);
                        if (p == null) return new long[] { ENOTCAPABLE };
                        return new long[] { stat(inst.memory(), p, ((int) a[1] & SYMLINK_FOLLOW) != 0, (int) a[4]) };
                    });
                    break;
                case "fd_readdir":
                    fs[i] = new HostFunction(f.module(), f.name(), f.paramTypes(), f.returnTypes(), (inst, a) -> {
                        Path dir = w.path((int) a[0]);
                        if (dir == null) return f.handle().apply(inst, a);
                        return new long[] { readdir(inst.memory(), dir, (int) a[1], (int) a[2], a[3], (int) a[4]) };
                    });
                    break;
                default:
            }
        }
        return fs;
    }

    /** The path of an fd (a file or directory), or null (none, or no path: a stream). */
    private Path path(int fd) {
        try {
            Object d = get.invoke(descriptors.get(wasi), fd);
            if (d == null) return null;
            Method m = d.getClass().getDeclaredMethod("path");
            m.setAccessible(true);
            return (Path) m.invoke(d);
        } catch (ReflectiveOperationException | RuntimeException x) {
            return null;
        }
    }

    /** name under dir, as Endive's resolvePath: relative, and not out of dir; else null. */
    static Path resolve(Path dir, String name) {
        Path n;
        try {
            n = dir.getFileSystem().getPath(name);
        } catch (RuntimeException x) {
            return null;
        }
        if (n.isAbsolute()) return null;
        String s = n.normalize().toString().replace('\\', '/');
        if (s.equals("..") || s.startsWith("../")) return null;
        return dir.resolve(s);
    }

    /** The filestat (64 bytes) of p at buf. */
    static int stat(Memory m, Path p, boolean follow, int buf) {
        try {
            LinkOption[] lo = follow ? new LinkOption[0] : new LinkOption[] { LinkOption.NOFOLLOW_LINKS };
            BasicFileAttributes b = Files.readAttributes(p, BasicFileAttributes.class, lo);
            ByteBuffer o = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            o.putLong(0, dev(p));
            o.putLong(8, ino(p));
            o.put(16, (byte) type(b));
            o.putLong(24, 1);
            o.putLong(32, b.size());
            o.putLong(40, b.lastAccessTime().to(TimeUnit.NANOSECONDS));
            o.putLong(48, b.lastModifiedTime().to(TimeUnit.NANOSECONDS));
            o.putLong(56, b.lastModifiedTime().to(TimeUnit.NANOSECONDS));
            m.write(buf, o.array());
            return ESUCCESS;
        } catch (IOException | RuntimeException x) {
            return errno(x);
        }
    }

    /** fd_readdir as Endive's: ".", "..", then the entries; cookie: how many to skip. */
    static int readdir(Memory m, Path dir, int buf, int bufLen, long cookie, int bufUsed) {
        if (cookie < 0) return EINVAL;
        int used = 0;
        List<Path> all = new ArrayList<>();
        all.add(dir.resolve("."));
        all.add(dir.resolve(".."));
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(all::add);
        } catch (IOException | RuntimeException x) {
            return errno(x);
        }
        for (long i = cookie; i < all.size() && used < bufLen; i++) {
            Path e = all.get((int) i);
            byte[] name = (i == 0 ? "." : i == 1 ? ".." : e.getFileName().toString()).getBytes(StandardCharsets.UTF_8);
            int type;
            try {
                type = type(Files.readAttributes(e, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
            } catch (IOException | RuntimeException x) {
                type = T_UNKNOWN;
            }
            ByteBuffer d = ByteBuffer.allocate(24 + name.length).order(ByteOrder.LITTLE_ENDIAN);
            d.putLong(0, i + 1);
            d.putLong(8, ino(e));
            d.putInt(16, name.length);
            d.put(20, (byte) type);
            d.position(24);
            d.put(name);
            int n = Math.min(d.capacity(), bufLen - used);
            m.write(buf + used, d.array(), 0, n);
            used += n;
        }
        m.writeI32(bufUsed, used);
        return ESUCCESS;
    }

    static int type(BasicFileAttributes b) {
        return b.isSymbolicLink() ? T_LNK : b.isDirectory() ? T_DIR : b.isRegularFile() ? T_REG : T_UNKNOWN;
    }

    /** The drive's letter (C is 3); no device numbers on Windows. */
    static long dev(Path p) {
        Path r = p.toAbsolutePath().getRoot();
        String s = r == null ? "" : r.toString();
        return s.length() >= 2 && s.charAt(1) == ':' ? Character.toUpperCase(s.charAt(0)) - 'A' + 1 : 0;
    }

    /** No inode numbers on Windows: a hash of the real path (case-folded), never 0. */
    static long ino(Path p) {
        String s;
        try {
            s = p.toRealPath().toString();
        } catch (IOException | RuntimeException x) {
            s = p.toAbsolutePath().normalize().toString();
        }
        long h = 1125899906842597L;
        for (char c : s.toUpperCase(java.util.Locale.ROOT).toCharArray()) h = 31 * h + c;
        return h == 0 ? 1 : h;
    }

    static int errno(Throwable x) {
        if (x instanceof NoSuchFileException) return ENOENT;
        if (x instanceof NotDirectoryException) return ENOTDIR;
        if (x instanceof AccessDeniedException) return EACCES;
        return EIO;
    }
}
