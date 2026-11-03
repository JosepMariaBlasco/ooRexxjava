package repro;

import run.endive.runtime.ByteArrayMemory;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.types.MemoryLimits;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Endive's WASI on Windows: path_filestat_get and fd_readdir on a regular file and
 * a directory, called directly from Java (no wasm module needed). They read the
 * file's attributes as "unix:*", a view the default file system has on Linux and
 * macOS but not on Windows, where Files.readAttributes throws
 * UnsupportedOperationException and both give ENOTSUP (58) for every file.
 * fd_filestat_get does the same for open files and directories.
 *
 *   StatAttributes        prints the errno of each call (0 is success)
 */
public final class StatAttributes {
    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("endive-stat");
        Files.writeString(dir.resolve("a.txt"), "abc");
        Files.createDirectory(dir.resolve("sub"));
        var opts = WasiOptions.builder().withDirectory("/", dir).build();
        var mem = new ByteArrayMemory(new MemoryLimits(1));
        try (var wasi = WasiPreview1.builder().withOptions(opts).build()) {
            // fd 3 is the preopened directory
            System.out.println(System.getProperty("os.name") + ", Java " + System.getProperty("java.version"));
            System.out.println("path_filestat_get(3, \"a.txt\"): errno " + wasi.pathFilestatGet(mem, 3, 0, "a.txt", 0)
                + ", size " + mem.readLong(32));
            System.out.println("path_filestat_get(3, \"sub\"):   errno " + wasi.pathFilestatGet(mem, 3, 0, "sub", 0));
            System.out.println("fd_readdir(3):                 errno " + wasi.fdReaddir(mem, 3, 1024, 4096, 0, 512)
                + ", " + mem.readInt(512) + " bytes");
        }
        System.out.println("(the unix view: " + probe(dir.resolve("a.txt")) + ")");
    }

    static String probe(Path p) {
        try {
            Files.readAttributes(p, "unix:*");
            return "the unix view is there";
        } catch (UnsupportedOperationException x) {
            return "Files.readAttributes(p, \"unix:*\"): " + x;
        } catch (Exception x) {
            return x.toString();
        }
    }
}
