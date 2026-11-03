package rexxjvm;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A JVM that keeps the module warm and runs programs for {@code rexxjs}, the native
 * client ({@code client/rexxjs.c}): one hosted {@link Run} per connection.
 *
 * <pre>java ... rexxjvm.Server SOCKET</pre>
 *
 * <p>Started by the client when it finds no server, detached; one per user and build
 * (the client names the socket after the jar), only one at a time ({@code SOCKET.lock}).
 * It ends after {@code -Drexxjvm.server.idle} seconds (default 900) with no program
 * running.
 *
 * <p>The protocol, big-endian, strings as an int length and UTF-8 bytes. The client
 * sends {@code "RXJ1"}, its pid and ppid, its current directory, its arguments (count,
 * then each), its environment ({@code NAME=value}: count, then each), and for its
 * standard input, output and error a kind ({@code 'T'} a terminal, {@code 'F'} a
 * regular file, {@code 'P'} anything else) and, for a regular file, its offset. The
 * server opens them itself, as {@code /proc/PID/fd/0}, {@code 1} and {@code 2} (Linux;
 * the same user): output to a regular file is appended (the client then moves its
 * offset to the end), input from one starts at the client's offset. The commands the
 * program runs get the same files. Then the client may send {@code 'S'} and a signal
 * number, any number of times (a HALT, as natively), and the server answers once
 * {@code 'X'}, the exit code, and the offset its standard input reached (-1 if not a
 * regular file). If the client goes away first, the run is ended.
 */
public final class Server {
    private Server() {}

    private static final AtomicInteger running = new AtomicInteger();
    /** The runs in progress, for a stop (rexxjs --orx-server-stop). */
    private static final Set<Run> runs = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile Path socketPath;
    /** The server's own output (its log), whatever System.err is routed to. */
    private static final PrintStream LOG = System.err;

    /**
     * System.out, System.err and System.in, for the Java code of the runs (BSF4ooRexx's
     * Java side, the client's classes, the threads they start): the stream of the run of
     * the calling thread, the client's, as with rexxj, where they are the process's;
     * the server's own (its log, nothing) for a thread of no run.
     */
    static void route() {
        PrintStream out = System.out, err = System.err;
        InputStream in = System.in;
        System.setOut(new PrintStream(new RoutedOut(true, out), true));
        System.setErr(new PrintStream(new RoutedOut(false, err), true));
        System.setIn(new InputStream() {
            InputStream target() {
                Run r = runOfThread();
                return r != null && r.in != null ? r.in : in;
            }
            @Override public int read() throws IOException { return target().read(); }
            @Override public int read(byte[] b, int off, int len) throws IOException { return target().read(b, off, len); }
            @Override public int available() throws IOException { return target().available(); }
        });
    }

    /** The run the calling thread works for: the wasm code it runs, else its context class loader's. */
    static Run runOfThread() {
        Run r = Run.current();
        return r != null ? r : Run.forLoader(Thread.currentThread().getContextClassLoader());
    }

    static final class RoutedOut extends OutputStream {
        final boolean isOut;
        final OutputStream own;
        RoutedOut(boolean isOut, OutputStream own) { this.isOut = isOut; this.own = own; }
        OutputStream target() {
            Run r = runOfThread();
            OutputStream t = r == null ? null : isOut ? r.out : r.err;
            return t != null ? t : own;
        }
        @Override public void write(int b) throws IOException { target().write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { target().write(b, off, len); }
        @Override public void flush() throws IOException { target().flush(); }
    }
    private static volatile long lastEnd = System.nanoTime();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: rexxjvm.Server SOCKET");
            System.exit(2);
        }
        Path socket = Path.of(args[0]);
        if (RxApi.UNIX == null) {
            System.err.println("rexxjvm.Server: Unix domain sockets need Java 16 or later");
            System.exit(1);
        }
        // one server per socket: the lock is held for as long as it lives
        Path lockFile = Path.of(socket + ".lock");
        RandomAccessFile lf = new RandomAccessFile(lockFile.toFile(), "rw");
        RxApi.restrict(lockFile);
        FileLock lock = lf.getChannel().tryLock();
        // another server serves it; or one that is stopping (its socket gone): wait for it
        for (int i = 0; i < 100 && lock == null && !Files.exists(socket); i++) {
            Thread.sleep(100);
            lock = lf.getChannel().tryLock();
        }
        if (lock == null) return;
        socketPath = socket;
        Files.deleteIfExists(socket);          // a stale socket of a server that died
        ServerSocketChannel server = (ServerSocketChannel) RxApi.OPEN_SERVER.invoke(null, RxApi.UNIX);
        server.bind(address(socket));
        RxApi.restrict(socket);

        route();
        long idle = TimeUnit.SECONDS.toNanos(Long.getLong("rexxjvm.server.idle", 900));
        Thread watchdog = new Thread(() -> {
            for (;;) {
                try { Thread.sleep(5_000); } catch (InterruptedException x) { return; }
                if (running.get() == 0 && System.nanoTime() - lastEnd > idle) {
                    try { Files.deleteIfExists(socket); } catch (IOException x) { /* going anyway */ }
                    Runtime.getRuntime().halt(0);
                }
            }
        }, "rexxjvm-server-idle");
        watchdog.setDaemon(true);
        watchdog.start();

        for (;;) {
            SocketChannel c = server.accept();
            Thread t = new Thread(null, () -> serve(c), "rexxjvm-server-run", Run.STACK);
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * rexxjs --orx-server-stop: the runs in progress end (as by SIGTERM, 143), the socket
     * goes, so that the next client starts a new server, and the JVM ends.
     */
    static void stop(SocketChannel c) {
        try { Files.deleteIfExists(socketPath); } catch (IOException x) { /* going anyway */ }
        for (Run r : runs) r.end(128 + 15);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (Run r : runs) {
            long left = deadline - System.nanoTime();
            if (left <= 0) break;
            r.awaitThreads(left, TimeUnit.NANOSECONDS);
        }
        try { c.close(); } catch (IOException x) { /* gone */ }
        Runtime.getRuntime().halt(0);
    }

    static SocketAddress address(Path p) throws ReflectiveOperationException {
        return (SocketAddress) RxApi.ADDRESS_OF.invoke(null, p.toString());
    }

    /** One program, for one client. */
    static void serve(SocketChannel c) {
        running.incrementAndGet();
        List<AutoCloseable> open = new ArrayList<>();
        try {
            Wire w = new Wire(c);
            byte[] magic = w.bytes(4);
            String m = new String(magic, StandardCharsets.US_ASCII);
            if (m.equals("RXJS")) stop(c);
            if (!"RXJ1".equals(m)) return;
            int pid = w.readInt(), ppid = w.readInt();
            String cwd = w.string();
            List<String> args = new ArrayList<>();
            for (int i = w.readInt(); i > 0; i--) args.add(w.string());
            Map<String, String> env = new LinkedHashMap<>();
            for (int i = w.readInt(); i > 0; i--) {
                String e = w.string();
                int eq = e.indexOf('=');
                if (eq > 0) env.put(e.substring(0, eq), e.substring(eq + 1));
            }
            char[] kind = new char[3];
            long[] offset = new long[3];
            for (int fd = 0; fd < 3; fd++) {
                kind[fd] = (char) w.bytes(1)[0];
                offset[fd] = w.readLong();
            }

            String proc = "/proc/" + pid + "/fd/";
            FileChannel in = FileChannel.open(Path.of(proc + 0), StandardOpenOption.READ);
            open.add(in);
            if (kind[0] == 'F' && offset[0] > 0) in.position(offset[0]);
            FileChannel out = output(Path.of(proc + 1), kind[1]);
            open.add(out);
            FileChannel err = output(Path.of(proc + 2), kind[2]);
            open.add(err);

            Run.Options o = new Run.Options();
            o.args = args;
            o.env = env;
            o.cwd = cwd;
            o.pid = pid;
            o.ppid = ppid;
            o.in = Channels.newInputStream(in);
            o.out = Channels.newOutputStream(out);
            o.err = Channels.newOutputStream(err);
            o.inTty = kind[0] == 'T';
            o.outTty = kind[1] == 'T';
            o.errTty = kind[2] == 'T';
            o.cmdIn = ProcessBuilder.Redirect.from(new File(proc + 0));
            o.cmdOut = ProcessBuilder.Redirect.appendTo(new File(proc + 1));
            o.cmdErr = ProcessBuilder.Redirect.appendTo(new File(proc + 2));
            // the run's Java side in a class loader of its own: BSF4ooRexx and the client's
            // CLASSPATH, as rexxj puts them on its class path; nothing of it is shared
            // with other runs (BSF4ooRexx's statics, the client's classes)
            URLClassLoader loader = loader(env.get("CLASSPATH"), cwd, pid);
            open.add(loader);
            o.loader = loader;
            if (kind[0] == 'F') o.inFile = in;
            Thread.currentThread().setContextClassLoader(loader);
            Run run = new Run(o);
            runs.add(run);
            open.add(() -> runs.remove(run));

            // the client's signals, and its going away
            Thread listener = new Thread(() -> {
                try {
                    for (;;) {
                        byte[] b = w.bytes(1);
                        if (b[0] == 'S') run.signal(w.readInt());
                        else break;
                    }
                } catch (IOException x) {
                    // the client went away (or the answer was sent and it closed)
                }
                if (!run.ended()) run.end(128 + 9);
            }, "rexxjvm-server-client");
            listener.setDaemon(true);
            listener.setContextClassLoader(Server.class.getClassLoader());   // not one of the run's (endJavaThreads)
            listener.start();

            int rc = run.start();
            Thread.interrupted();                     // end() may have interrupted this thread
            run.awaitThreads(5, TimeUnit.SECONDS);
            endJavaThreads(loader, 2, TimeUnit.SECONDS);
            long inAt = -1;
            if (kind[0] == 'F') {
                try { inAt = in.position(); } catch (IOException x) { /* closed by an interrupt */ }
            }
            ByteBuffer answer = ByteBuffer.allocate(13);
            answer.put((byte) 'X').putInt(rc).putLong(inAt).flip();
            while (answer.hasRemaining()) c.write(answer);
        } catch (Throwable x) {
            LOG.println("rexxjvm.Server: " + x);
        } finally {
            for (AutoCloseable a : open) {
                try { a.close(); } catch (Exception x) { /* closing anyway */ }
            }
            try { c.close(); } catch (IOException x) { /* gone */ }
            lastEnd = System.nanoTime();
            running.decrementAndGet();
        }
    }

    /**
     * Interrupts the Java threads the run's Java side started and left running (their
     * context class loader is the run's, inherited from the thread that made them), as
     * the end of a process ends all its threads, and waits up to the timeout for them.
     * BSF4ooRexx's {@code RexxCleanupRef} cleaner, one per run, waits on a reference queue
     * for ever otherwise, and keeps the run's class loader, with all its classes, alive.
     */
    static void endJavaThreads(ClassLoader loader, long timeout, TimeUnit unit) {
        Thread self = Thread.currentThread();
        List<Thread> left = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t == self || !t.isAlive()) continue;
            for (ClassLoader l = t.getContextClassLoader(); l != null; l = l.getParent()) {
                if (l == loader) { left.add(t); t.interrupt(); break; }
            }
        }
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (Thread t : left) {
            long ms = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (ms <= 0) break;
            try { t.join(ms); } catch (InterruptedException x) { Thread.currentThread().interrupt(); break; }
        }
    }

    /**
     * The class loader of a run: {@code bsf4oorexx.jar} beside {@code rexxj.jar}, then
     * the entries of the client's {@code CLASSPATH}, as {@code java -cp} takes them:
     * relative to the client's directory, {@code dir/*} the jars in dir, an empty entry
     * the directory itself. Its parent has {@code rexxj.jar} alone (the server's class
     * path), so BSF4ooRexx and the client's classes are found here, once per run.
     */
    static URLClassLoader loader(String classpath, String cwd, int pid) throws IOException {
        List<URL> urls = new ArrayList<>();
        String home = Host.libraryDirectory();
        if (home != null) {
            File bsf = new File(home, "bsf4oorexx.jar");
            if (bsf.isFile()) urls.add(bsf.toURI().toURL());
        }
        if (classpath != null) {
            for (String e : classpath.split(":", -1)) {
                File f = new File(e.isEmpty() ? "." : e);
                if (!f.isAbsolute()) f = new File(cwd, f.getPath());
                if (f.getName().equals("*")) {
                    File[] jars = f.getParentFile().listFiles((d, n) -> n.endsWith(".jar") || n.endsWith(".JAR"));
                    if (jars != null) {
                        Arrays.sort(jars);
                        for (File j : jars) urls.add(j.toURI().toURL());
                    }
                } else {
                    urls.add(f.toURI().toURL());
                }
            }
        }
        return new URLClassLoader("rexxj-run-" + pid, urls.toArray(new URL[0]), Server.class.getClassLoader());
    }

    /** The client's output: appended if a regular file, so that commands' output and ours interleave. */
    static FileChannel output(Path p, char kind) throws IOException {
        return kind == 'F' ? FileChannel.open(p, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                           : FileChannel.open(p, StandardOpenOption.WRITE);
    }

    /**
     * Reads from the socket with the channel itself: its streams (Channels.newInputStream)
     * would hold a lock that blocks the answer written while a read waits.
     */
    static final class Wire {
        final SocketChannel c;
        Wire(SocketChannel c) { this.c = c; }

        byte[] bytes(int n) throws IOException {
            ByteBuffer b = ByteBuffer.allocate(n);
            while (b.hasRemaining()) if (c.read(b) < 0) throw new IOException("closed");
            return b.array();
        }

        int readInt() throws IOException { return ByteBuffer.wrap(bytes(4)).getInt(); }

        long readLong() throws IOException { return ByteBuffer.wrap(bytes(8)).getLong(); }

        String string() throws IOException {
            int n = readInt();
            if (n < 0 || n > (64 << 20)) throw new IOException("bad length " + n);
            return new String(bytes(n), StandardCharsets.UTF_8);
        }
    }
}
