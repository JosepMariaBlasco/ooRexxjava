package rexxjvm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Rexx API server shared between processes (the WASI patch's {@code SysHostConnection}):
 * stream connections over Unix domain sockets, one per interpreter version and user, in
 * {@code java.io.tmpdir}, that only the user can open (as {@code rxapi}'s); the daemon is
 * this launcher run as {@code rexx --orx-wasm-rxapi}, detached. Unix domain sockets came in
 * Java 16: they are looked up by reflection, so that the launcher still builds for Java 11,
 * where {@code api_available} says no and the interpreter uses its in-process server.
 * Also the real process ids, by which the server tells sessions apart.
 */
final class RxApi {
    private RxApi() {}

    static final ProtocolFamily UNIX;
    static final Method ADDRESS_OF, OPEN_SOCKET, OPEN_SERVER;
    static {
        ProtocolFamily f = null;
        Method of = null, open = null, openServer = null;
        try {
            f = StandardProtocolFamily.valueOf("UNIX");
            of = Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", String.class);
            open = SocketChannel.class.getMethod("open", ProtocolFamily.class);
            openServer = ServerSocketChannel.class.getMethod("open", ProtocolFamily.class);
        } catch (ReflectiveOperationException | IllegalArgumentException x) {
            f = null;
        }
        UNIX = f;
        ADDRESS_OF = of;
        OPEN_SOCKET = open;
        OPEN_SERVER = openServer;
    }

    static final Map<Integer, Object> handles = new ConcurrentHashMap<>();
    static final AtomicInteger nextHandle = new AtomicInteger(1);
    /** The daemon's lock: held for as long as it lives. */
    static FileLock serverLock;

    static List<HostFunction> functions() {
        List<ValType> i32 = List.of(ValType.I32), none = List.of();
        List<ValType> three = List.of(ValType.I32, ValType.I32, ValType.I32);
        return List.of(
            new HostFunction("rexxjvm", "process_id", i32, i32, (inst, a) -> new long[] { processId(a[0] != 0) }),
            new HostFunction("rexxjvm", "api_available", none, i32,
                (inst, a) -> new long[] { UNIX != null ? 1 : 0 }),
            new HostFunction("rexxjvm", "api_connect", i32, i32,
                (inst, a) -> new long[] { connect(service(inst.memory(), (int) a[0])) }),
            new HostFunction("rexxjvm", "api_listen", i32, i32,
                (inst, a) -> new long[] { listen(service(inst.memory(), (int) a[0])) }),
            new HostFunction("rexxjvm", "api_accept", i32, i32, (inst, a) -> new long[] { accept((int) a[0]) }),
            new HostFunction("rexxjvm", "api_read", three, i32,
                (inst, a) -> new long[] { read(inst.memory(), (int) a[0], (int) a[1], (int) a[2]) }),
            new HostFunction("rexxjvm", "api_write", three, i32,
                (inst, a) -> new long[] { write(inst.memory(), (int) a[0], (int) a[1], (int) a[2]) }),
            new HostFunction("rexxjvm", "api_close", i32, none, (inst, a) -> { close((int) a[0]); return null; }),
            new HostFunction("rexxjvm", "api_start_server", none, i32, (inst, a) -> new long[] { startServer() }));
    }

    static int processId(boolean parent) {
        Run r = Run.current();
        if (r != null && (parent ? r.ppid : r.pid) >= 0) return (int) (parent ? r.ppid : r.pid);
        ProcessHandle p = ProcessHandle.current();
        long pid = parent ? p.parent().map(ProcessHandle::pid).orElse(1L) : p.pid();
        return (int) pid;
    }

    /** The socket of a service: {@code <tmpdir>/.ooRexx-jvm-<user>-<service>}. */
    static Path service(Memory m, int p) {
        String service = m.readCString(p, StandardCharsets.UTF_8).replaceAll("[^A-Za-z0-9._-]", "_");
        String user = System.getProperty("user.name", "user").replaceAll("[^A-Za-z0-9._-]", "_");
        return Path.of(System.getProperty("java.io.tmpdir"), ".ooRexx-jvm-" + user + "-" + service);
    }

    static SocketAddress address(Path p) throws ReflectiveOperationException {
        return (SocketAddress) ADDRESS_OF.invoke(null, p.toString());
    }

    static int register(Object channel) {
        int h = nextHandle.getAndIncrement();
        handles.put(h, channel);
        Run r = Run.current();
        if (r != null) r.apiHandles.add(h);
        return h;
    }

    static int connect(Path p) {
        if (UNIX == null) return -1;
        try {
            SocketChannel c = (SocketChannel) OPEN_SOCKET.invoke(null, UNIX);
            try {
                c.connect(address(p));
            } catch (IOException x) {
                c.close();
                return -1;
            }
            return register(c);
        } catch (ReflectiveOperationException | IOException x) {
            return -1;
        }
    }

    /**
     * Serve the service: -1 if another daemon does (its lock is held, or its socket answers).
     * The lock file is never removed (as rxapi's), so that it always means the same file.
     */
    static int listen(Path p) {
        if (UNIX == null) return -1;
        try {
            Path lock = Path.of(p + ".lock");
            RandomAccessFile f = new RandomAccessFile(lock.toFile(), "rw");
            restrict(lock);
            FileLock l = f.getChannel().tryLock();
            if (l == null) {
                f.close();
                return -1;
            }
            int probe = connect(p);
            if (probe >= 0) {             // a server without the lock (should not happen): leave it
                close(probe);
                l.release();
                f.close();
                return -1;
            }
            Files.deleteIfExists(p);      // a stale socket of a server that died
            ServerSocketChannel s = (ServerSocketChannel) OPEN_SERVER.invoke(null, UNIX);
            s.bind(address(p));
            restrict(p);
            serverLock = l;
            return register(s);
        } catch (ReflectiveOperationException | IOException x) {
            return -1;
        }
    }

    static void restrict(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException x) {
            // not a POSIX file system: what tmpdir gives
        }
    }

    static int accept(int h) {
        Object s = handles.get(h);
        if (!(s instanceof ServerSocketChannel)) return -1;
        try {
            return register(((ServerSocketChannel) s).accept());
        } catch (IOException x) {
            return -1;
        }
    }

    /** Up to size bytes into buf, blocking until there is at least one; 0: closed. */
    static int read(Memory m, int h, int buf, int size) {
        Object c = handles.get(h);
        if (!(c instanceof SocketChannel)) return -1;
        try {
            ByteBuffer b = ByteBuffer.allocate(Math.min(size, 1 << 16));
            int n = ((SocketChannel) c).read(b);
            if (n <= 0) return 0;
            m.write(buf, b.array(), 0, n);
            return n;
        } catch (IOException x) {
            return -1;
        }
    }

    /** All of it, or -1. */
    static int write(Memory m, int h, int buf, int size) {
        Object c = handles.get(h);
        if (!(c instanceof SocketChannel)) return -1;
        try {
            ByteBuffer b = ByteBuffer.wrap(m.readBytes(buf, size));
            while (b.hasRemaining()) ((SocketChannel) c).write(b);
            return size;
        } catch (IOException x) {
            return -1;
        }
    }

    static void close(int h) {
        Run r = Run.current();
        if (r != null) r.apiHandles.remove(h);
        Object c = handles.remove(h);
        try {
            if (c instanceof SocketChannel) ((SocketChannel) c).close();
            else if (c instanceof ServerSocketChannel) ((ServerSocketChannel) c).close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    /**
     * Launch the daemon, detached: the same Java and the same launcher, with
     * {@code --orx-wasm-rxapi}; in a session of its own if {@code setsid} is there (so that
     * the terminal's Ctrl-C does not reach it), and without the caller's standard streams
     * (it must not hold a pipe its caller's caller waits on).
     */
    static int startServer() {
        try {
            List<String> cmd = new ArrayList<>();
            for (String s : new String[] { "/usr/bin/setsid", "/bin/setsid" }) {
                if (new File(s).canExecute()) {
                    cmd.add(s);
                    break;
                }
            }
            cmd.add(ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
            for (String k : new String[] { "rexxjvm.executable", "rexxjvm.home", "rexxjvm.stack", "rexxjvm.interp" }) {
                String v = System.getProperty(k);
                if (v != null) cmd.add("-D" + k + "=" + v);
            }
            cmd.add("-cp");
            cmd.add(new File(RxApi.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
            cmd.add(RunWasm.class.getName());
            Run run = Run.current();
            if (run != null && run.module.file != null) cmd.add(run.module.file);
            cmd.add("--orx-wasm-rxapi");
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(new File("/"));
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            return 0;
        } catch (Exception x) {
            return -1;
        }
    }
}
