package rexxjvm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A server's run whose client's standard streams are relayed through the socket, where
 * the server cannot open them itself (no {@code /proc/PID/fd}: macOS, Windows; on Linux
 * when the client asks, {@code REXXJ_SERVER_RELAY}). After the request, the socket
 * carries frames, a type byte and its fields (big-endian; {@link Server} has the request).
 *
 * <p>From the server: {@code 'O'} and {@code 'E'} (int length, bytes): for the client's
 * standard output and error. {@code 'R'} (int max): read once, up to max bytes, from the
 * client's standard input: the client reads only what the program asks for, so what
 * follows it (a command, the next program) finds the rest, as natively. {@code 'C'} (int
 * id, argv, environment, directory, int flags, input): run a command; the client starts
 * it with its own standard streams, so a command gets the client's terminal, pipes and
 * files as natively, but for those the program captures ({@link Host#commandRun}).
 * {@code 'K'} (int id, int signal): signal that command. {@code 'X'} (int code, long -1):
 * the end.
 *
 * <p>From the client: {@code 'S'} (int signal), as in the other mode. {@code 'I'} (int
 * length, bytes): what one {@code 'R'} read; 0 is the end of the file, -1 an error.
 * {@code 'o'} and {@code 'e'} (int id, int length, bytes): a command's captured output
 * and error. {@code 'D'} (int id, int status): the command ended (its exit code, 128+n
 * for a signal n; {@link #NOT_STARTED} if it could not be started: on Windows an exit
 * code is any 32-bit value, -1 too).
 */
final class Relay {
    static final int CHUNK = 64 << 10;
    /** Command flags in a 'C' frame. */
    static final int CAPTURE_OUT = 1, CAPTURE_ERR = 2, SAME = 4, INPUT = 8;
    /** A 'D' frame's status for a command that could not be started. */
    static final int NOT_STARTED = Integer.MIN_VALUE;

    private final SocketChannel c;
    private volatile boolean closed;

    /** The client's standard input, standard output and error, for the run. */
    final InputStream in = new In();
    final OutputStream out = new Out('O'), err = new Out('E');

    Relay(SocketChannel c) {
        this.c = c;
        Thread t = new Thread(this::write, "rexxjvm-server-relay");
        t.setDaemon(true);
        t.setContextClassLoader(Relay.class.getClassLoader());   // not one of the run's (endJavaThreads)
        t.start();
    }

    // ---- writing frames ---------------------------------------------------------------

    /**
     * The frames to write, in order, by one thread of the relay's own: a thread
     * interrupted while it writes to a channel closes it (ClosedByInterruptException),
     * and the run's threads are interrupted when it ends, as they write its last output.
     * Bounded: a client that does not keep up holds the run's writes back.
     */
    private final LinkedBlockingQueue<ByteBuffer[]> frames = new LinkedBlockingQueue<>(256);
    private static final ByteBuffer[] END = new ByteBuffer[0];
    private final java.util.concurrent.CountDownLatch written = new java.util.concurrent.CountDownLatch(1);

    private void write() {
        // the frames waiting go out in one write: many small ones (a program's lines) at once
        List<ByteBuffer[]> batch = new java.util.ArrayList<>();
        List<ByteBuffer> parts = new java.util.ArrayList<>();
        try {
            for (boolean end = false; !end; ) {
                batch.add(frames.take());
                frames.drainTo(batch, 255);
                parts.clear();
                for (ByteBuffer[] f : batch) {
                    if (f == END) { end = true; break; }
                    Collections.addAll(parts, f);
                }
                batch.clear();
                ByteBuffer[] all = parts.toArray(new ByteBuffer[0]);
                for (int i = 0; i < all.length; ) {
                    c.write(all, i, all.length - i);
                    while (i < all.length && !all[i].hasRemaining()) i++;
                }
            }
        } catch (IOException | InterruptedException x) {
            closed = true;
            frames.clear();
        } finally {
            written.countDown();
        }
    }

    /** Queues a frame; an interrupted thread's too, so that a run's last output is not lost. */
    private void send(ByteBuffer... parts) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            while (!closed) {
                try {
                    if (frames.offer(parts, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException x) {
                    interrupted = true;
                }
            }
            throw new IOException("the client went away");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** The answer at the end, as in the other mode: 'X', the exit code, -1; once written. */
    void exit(int rc) throws IOException {
        send(ByteBuffer.allocate(13).put((byte) 'X').putInt(rc).putLong(-1).flip());
        send(END);
        try {
            written.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
        }
    }

    /** The relay is no longer needed (the run ended without exit): its writer ends. */
    void close() {
        closed = true;
        frames.clear();
        frames.offer(END);
    }

    final class Out extends OutputStream {
        final byte type;
        Out(char type) { this.type = (byte) type; }
        @Override public void write(int b) throws IOException { write(new byte[] { (byte) b }, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, CHUNK);
                // copied: it is written later, and the caller may reuse its array
                send(ByteBuffer.allocate(5 + n).put(type).putInt(n).put(b, off, n).flip());
                off += n;
                len -= n;
            }
        }
    }

    // ---- standard input ---------------------------------------------------------------

    /** What the 'I' frames bring; CLOSED when the client goes away. */
    private final LinkedBlockingQueue<byte[]> answers = new LinkedBlockingQueue<>();
    private static final byte[] CLOSED = new byte[0], ERROR = new byte[0];

    final class In extends InputStream {
        private byte[] left;                     // what a read asked for and did not take
        private int at;
        private int asked;                       // 'R' frames not answered yet

        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
        }

        @Override public synchronized int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (left == null) {
                // a read interrupted before its answer came leaves it asked: wait for that one
                if (asked == 0) {
                    send(ByteBuffer.allocate(5).put((byte) 'R').putInt(Math.min(len, CHUNK)).flip());
                    asked++;
                }
                byte[] a;
                try {
                    a = answers.take();
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
                asked--;
                if (a == CLOSED) { answers.add(CLOSED); throw new IOException("the client went away"); }
                if (a == ERROR) throw new IOException("the client could not read its standard input");
                if (a.length == 0) return -1;
                left = a;
                at = 0;
            }
            int n = Math.min(len, left.length - at);
            System.arraycopy(left, at, b, off, n);
            at += n;
            if (at == left.length) left = null;
            return n;
        }

        @Override public synchronized int available() { return left == null ? 0 : left.length - at; }
    }

    // ---- commands ---------------------------------------------------------------------

    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, Command> commands = new ConcurrentHashMap<>();

    static final class Command {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        final CompletableFuture<Integer> done = new CompletableFuture<>();
    }

    /**
     * Runs a command on the client, with the client's standard streams but for the ones
     * captured: output (into rs[0]), error (into rs[1], or with the output if same), and
     * input given (if input is not null). Returns its status; {@link #NOT_STARTED} if it
     * could not start.
     */
    int command(List<String> argv, Map<String, String> env, String cwd, byte[] input,
                boolean captureOut, boolean captureErr, boolean same, byte[][] rs) throws IOException {
        int id = ids.incrementAndGet();
        Command cmd = new Command();
        commands.put(id, cmd);
        try {
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            java.io.DataOutputStream d = new java.io.DataOutputStream(f);
            d.writeByte('C');
            d.writeInt(id);
            d.writeInt(argv.size());
            for (String a : argv) string(d, a);
            d.writeInt(env.size());
            for (Map.Entry<String, String> e : env.entrySet()) string(d, e.getKey() + "=" + e.getValue());
            string(d, cwd == null ? "" : cwd);
            d.writeInt((captureOut ? CAPTURE_OUT : 0) | (captureErr ? CAPTURE_ERR : 0)
                       | (same ? SAME : 0) | (input != null ? INPUT : 0));
            byte[] i = input == null ? new byte[0] : input;
            d.writeInt(i.length);
            d.write(i);
            d.flush();
            send(ByteBuffer.wrap(f.toByteArray()));
            int rc;
            try {
                rc = cmd.done.get();
            } catch (InterruptedException x) {
                // the run is ending: the command too, as the process's end would leave it a SIGTERM
                try { send(ByteBuffer.allocate(9).put((byte) 'K').putInt(id).putInt(15).flip()); }
                catch (IOException y) { /* gone */ }
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            } catch (ExecutionException x) {
                throw new IOException("the client went away");
            }
            if (captureOut) rs[0] = cmd.out.toByteArray();
            if (captureErr) rs[1] = cmd.err.toByteArray();
            return rc;
        } finally {
            commands.remove(id);
        }
    }

    private static void string(java.io.DataOutputStream d, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        d.writeInt(b.length);
        d.write(b);
    }

    // ---- reading frames ---------------------------------------------------------------

    /**
     * Reads the client's frames until it goes away (or closes after the answer): signals
     * to the run, the rest to whoever waits for it. Never blocks on anything but the
     * socket, so that the client's frames are always taken while the run writes.
     */
    void listen(Server.Wire w, Run run) {
        try {
            for (;;) {
                byte t = w.bytes(1)[0];
                if (t == 'S') {
                    run.signal(w.readInt());
                } else if (t == 'I') {
                    int n = w.readInt();
                    if (n > CHUNK) throw new IOException("bad length " + n);
                    answers.add(n < 0 ? ERROR : w.bytes(n));
                } else if (t == 'o' || t == 'e') {
                    int id = w.readInt(), n = w.readInt();
                    if (n < 0 || n > CHUNK) throw new IOException("bad length " + n);
                    byte[] b = w.bytes(n);
                    Command cmd = commands.get(id);
                    if (cmd != null) (t == 'o' ? cmd.out : cmd.err).write(b);
                } else if (t == 'D') {
                    int id = w.readInt(), status = w.readInt();
                    Command cmd = commands.get(id);
                    if (cmd != null) cmd.done.complete(status);
                } else {
                    throw new IOException("bad frame " + t);
                }
            }
        } catch (IOException x) {
            // the client went away (or the answer was sent and it closed)
        } finally {
            closed = true;
            answers.add(CLOSED);
            for (Command cmd : commands.values()) cmd.done.completeExceptionally(new IOException("closed"));
        }
    }
}
