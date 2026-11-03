package rexxjvm;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.SocketAddress;
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
        if (lock == null) return;              // another server serves it
        Files.deleteIfExists(socket);          // a stale socket of a server that died
        ServerSocketChannel server = (ServerSocketChannel) RxApi.OPEN_SERVER.invoke(null, RxApi.UNIX);
        server.bind(address(socket));
        RxApi.restrict(socket);

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
            if (!"RXJ1".equals(new String(magic, StandardCharsets.US_ASCII))) return;
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
            Run run = new Run(o);

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
            listener.start();

            int rc = run.start();
            Thread.interrupted();                     // end() may have interrupted this thread
            run.awaitThreads(5, TimeUnit.SECONDS);
            long inAt = -1;
            if (kind[0] == 'F') {
                try { inAt = in.position(); } catch (IOException x) { /* closed by an interrupt */ }
            }
            ByteBuffer answer = ByteBuffer.allocate(13);
            answer.put((byte) 'X').putInt(rc).putLong(inAt).flip();
            while (answer.hasRemaining()) c.write(answer);
        } catch (Throwable x) {
            System.err.println("rexxjvm.Server: " + x);
        } finally {
            for (AutoCloseable a : open) {
                try { a.close(); } catch (Exception x) { /* closing anyway */ }
            }
            try { c.close(); } catch (IOException x) { /* gone */ }
            lastEnd = System.nanoTime();
            running.decrementAndGet();
        }
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
