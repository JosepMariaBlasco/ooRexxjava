package rexxjvm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetBoundException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IPv4 sockets for rxsock ({@code compat/orx_wasi_net.c}, which says how): WASI preview 1
 * has none. Every socket is a non-blocking java.nio channel; blocking calls wait on a
 * selector of the socket's own, with the socket's timeout. A stream socket becomes a
 * client (connect) or a server (listen) when it is used; bind only remembers the address
 * until then. Addresses are IPv4, as host-order ints. Functions return a count, a handle
 * or 0, or a negative error code (orx_wasi_net.c maps it to errno).
 */
final class Net {
    // error codes (negated), as orx_wasi_net.c maps them
    static final int EBADF = 1, EAGAIN = 2, ECONNREFUSED = 3, EADDRINUSE = 4, EADDRNOTAVAIL = 5,
        ETIMEDOUT = 6, ENOTCONN = 7, ECONNRESET = 8, EPIPE = 9, EHOSTUNREACH = 10, ENETUNREACH = 11,
        EINVAL = 12, EIO = 13, EACCES = 14, EISCONN = 15, EOPNOTSUPP = 16;

    // orx_wasi_net.c's option codes
    static final int O_REUSEADDR = 1, O_KEEPALIVE = 2, O_RCVBUF = 3, O_SNDBUF = 4, O_LINGER = 5,
        O_BROADCAST = 6, O_OOBINLINE = 7, O_NODELAY = 8;

    static final class Sock {
        final boolean stream;
        SocketChannel client;
        ServerSocketChannel server;
        DatagramChannel datagram;
        InetSocketAddress local;          // bind's address, until the socket is used
        Selector selector;                // for waiting on this socket alone
        final ByteBuffer pending = ByteBuffer.allocate(65536);   // read ahead (FIONREAD); flipped: readable
        final Map<Integer, Integer> options = new ConcurrentHashMap<>();

        Sock(boolean stream) {
            this.stream = stream;
            pending.flip();
        }

        SelectableChannel channel() {
            return client != null ? client : server != null ? server : datagram;
        }
    }

    static final Map<Integer, Sock> socks = new ConcurrentHashMap<>();
    static final AtomicInteger next = new AtomicInteger(1);

    private Net() {}

    static List<HostFunction> functions() {
        List<ValType> i32 = List.of(ValType.I32);
        List<ValType> two = List.of(ValType.I32, ValType.I32);
        List<ValType> three = List.of(ValType.I32, ValType.I32, ValType.I32);
        List<ValType> four = List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32);
        List<ValType> five = List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32);
        List<ValType> six = List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32);
        return List.of(
            new HostFunction("rexxjvm", "net_socket", i32, i32, (inst, a) -> new long[] { socket((int) a[0] == 1) }),
            new HostFunction("rexxjvm", "net_bind", three, i32,
                (inst, a) -> new long[] { bind((int) a[0], (int) a[1], (int) a[2]) }),
            new HostFunction("rexxjvm", "net_listen", two, i32, (inst, a) -> new long[] { listen((int) a[0], (int) a[1]) }),
            new HostFunction("rexxjvm", "net_connect", four, i32,
                (inst, a) -> new long[] { connect((int) a[0], (int) a[1], (int) a[2], (int) a[3]) }),
            new HostFunction("rexxjvm", "net_accept", three, i32,
                (inst, a) -> new long[] { accept(inst.memory(), (int) a[0], (int) a[1], (int) a[2]) }),
            new HostFunction("rexxjvm", "net_send", five, i32,
                (inst, a) -> new long[] { send(inst.memory(), (int) a[0], (int) a[1], (int) a[2], (int) a[3], (int) a[4]) }),
            new HostFunction("rexxjvm", "net_recv", five, i32,
                (inst, a) -> new long[] { recv(inst.memory(), (int) a[0], (int) a[1], (int) a[2], (int) a[3], (int) a[4]) }),
            new HostFunction("rexxjvm", "net_sendto", six, i32,
                (inst, a) -> new long[] { sendto(inst.memory(), (int) a[0], (int) a[1], (int) a[2], (int) a[3], (int) a[4], (int) a[5]) }),
            new HostFunction("rexxjvm", "net_recvfrom", five, i32,
                (inst, a) -> new long[] { recvfrom(inst.memory(), (int) a[0], (int) a[1], (int) a[2], (int) a[3], (int) a[4]) }),
            new HostFunction("rexxjvm", "net_shutdown", two, i32, (inst, a) -> new long[] { shutdown((int) a[0], (int) a[1]) }),
            new HostFunction("rexxjvm", "net_close", i32, i32, (inst, a) -> new long[] { close((int) a[0]) }),
            new HostFunction("rexxjvm", "net_name", three, i32,
                (inst, a) -> new long[] { name(inst.memory(), (int) a[0], (int) a[1] != 0, (int) a[2]) }),
            new HostFunction("rexxjvm", "net_setopt", three, i32,
                (inst, a) -> new long[] { setopt((int) a[0], (int) a[1], (int) a[2]) }),
            new HostFunction("rexxjvm", "net_available", i32, i32, (inst, a) -> new long[] { available((int) a[0]) }),
            new HostFunction("rexxjvm", "net_select", six, i32,
                (inst, a) -> new long[] { select(inst.memory(), (int) a[0], (int) a[1], (int) a[2], (int) a[3], (int) a[4], (int) a[5]) }),
            new HostFunction("rexxjvm", "net_resolve", four, i32,
                (inst, a) -> new long[] { resolve(inst.memory(), (int) a[0], (int) a[1], (int) a[2], (int) a[3]) }));
    }

    static InetAddress ip(int a) throws UnknownHostException {
        return InetAddress.getByAddress(new byte[] { (byte) (a >>> 24), (byte) (a >>> 16), (byte) (a >>> 8), (byte) a });
    }

    static int ip(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length != 4) return 0;
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    /** an IPv4 address and port into memory: int address, int port */
    static void putAddress(Memory m, int out, SocketAddress sa) {
        if (out == 0) return;
        int addr = 0, port = 0;
        if (sa instanceof InetSocketAddress) {
            InetSocketAddress i = (InetSocketAddress) sa;
            if (i.getAddress() != null) addr = ip(i.getAddress());
            port = i.getPort();
        }
        m.writeI32(out, addr);
        m.writeI32(out + 4, port);
    }

    static int socket(boolean stream) {
        Sock s = new Sock(stream);
        if (!stream) {
            try {
                s.datagram = DatagramChannel.open(java.net.StandardProtocolFamily.INET);
                s.datagram.configureBlocking(false);
            } catch (IOException | RuntimeException x) {
                return -error(x);
            }
        }
        int h = next.getAndIncrement();
        socks.put(h, s);
        own(h);
        return h;
    }

    static int bind(int h, int addr, int port) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        try {
            InetSocketAddress a = new InetSocketAddress(ip(addr), port);
            if (s.datagram != null) {
                s.datagram.bind(a);
            } else if (s.client != null || s.server != null) {
                return -EINVAL;
            } else {
                // a stream socket becomes a server or a client later; check the address now
                // (EADDRINUSE, EADDRNOTAVAIL at bind, as natively), unless SO_REUSEADDR is on
                if (!(s.options.getOrDefault(O_REUSEADDR, 0) != 0)) {
                    try (ServerSocketChannel probe = ServerSocketChannel.open()) {
                        probe.bind(a, 1);
                        if (port == 0) a = (InetSocketAddress) probe.getLocalAddress();
                    }
                }
                s.local = a;
            }
            return 0;
        } catch (IOException | RuntimeException x) {
            return -error(x);
        }
    }

    static void apply(Sock s) throws IOException {
        for (Map.Entry<Integer, Integer> e : s.options.entrySet()) apply(s, e.getKey(), e.getValue());
    }

    static void apply(Sock s, int opt, int v) throws IOException {
        if (s.client != null) {
            SocketChannel c = s.client;
            switch (opt) {
                case O_REUSEADDR: c.setOption(StandardSocketOptions.SO_REUSEADDR, v != 0); break;
                case O_KEEPALIVE: c.setOption(StandardSocketOptions.SO_KEEPALIVE, v != 0); break;
                case O_RCVBUF: if (v > 0) c.setOption(StandardSocketOptions.SO_RCVBUF, v); break;
                case O_SNDBUF: if (v > 0) c.setOption(StandardSocketOptions.SO_SNDBUF, v); break;
                case O_LINGER: c.setOption(StandardSocketOptions.SO_LINGER, v); break;
                case O_NODELAY: c.setOption(StandardSocketOptions.TCP_NODELAY, v != 0); break;
                case O_OOBINLINE: c.socket().setOOBInline(v != 0); break;
                default: break;
            }
        } else if (s.server != null) {
            switch (opt) {
                case O_REUSEADDR: s.server.setOption(StandardSocketOptions.SO_REUSEADDR, v != 0); break;
                case O_RCVBUF: if (v > 0) s.server.setOption(StandardSocketOptions.SO_RCVBUF, v); break;
                default: break;
            }
        } else if (s.datagram != null) {
            DatagramChannel d = s.datagram;
            switch (opt) {
                case O_REUSEADDR: d.setOption(StandardSocketOptions.SO_REUSEADDR, v != 0); break;
                case O_RCVBUF: if (v > 0) d.setOption(StandardSocketOptions.SO_RCVBUF, v); break;
                case O_SNDBUF: if (v > 0) d.setOption(StandardSocketOptions.SO_SNDBUF, v); break;
                case O_BROADCAST: d.setOption(StandardSocketOptions.SO_BROADCAST, v != 0); break;
                default: break;
            }
        }
    }

    /** opt, value: as orx_wasi_net.c remembers them; applied now if the channel exists, else when it does. */
    static int setopt(int h, int opt, int v) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        s.options.put(opt, v);
        try {
            apply(s, opt, v);
            return 0;
        } catch (IOException | RuntimeException x) {
            return 0;   // remembered; a channel that refuses it is not an error for rxsock
        }
    }

    static int listen(int h, int backlog) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        if (!s.stream || s.client != null) return -EOPNOTSUPP;
        if (s.server != null) return 0;
        try {
            ServerSocketChannel c = ServerSocketChannel.open();
            s.server = c;
            apply(s);
            c.bind(s.local != null ? s.local : new InetSocketAddress(0), backlog > 0 ? backlog : 50);
            c.configureBlocking(false);
            return 0;
        } catch (IOException | RuntimeException x) {
            closeQuietly(s);
            s.server = null;
            return -error(x);
        }
    }

    static int connect(int h, int addr, int port, int timeoutMs) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        try {
            InetSocketAddress to = new InetSocketAddress(ip(addr), port);
            if (s.datagram != null) {
                s.datagram.connect(to);
                return 0;
            }
            if (s.server != null) return -EOPNOTSUPP;
            if (s.client != null) return -EISCONN;
            SocketChannel c = SocketChannel.open();
            s.client = c;
            apply(s);
            if (s.local != null) c.bind(s.local);
            c.socket().connect(to, Math.max(timeoutMs, 0));
            c.configureBlocking(false);
            return 0;
        } catch (IOException | RuntimeException x) {
            if (s.client != null) {
                try { s.client.close(); } catch (IOException y) { /* gone */ }
                s.client = null;
            }
            return -error(x);
        }
    }

    /** Waits until the socket is ready for ops; timeoutMs < 0 forever. False on timeout. */
    static boolean await(Sock s, int ops, int timeoutMs) throws IOException {
        if (s.selector == null) s.selector = Selector.open();
        SelectionKey k = s.channel().keyFor(s.selector);
        if (k == null) k = s.channel().register(s.selector, ops);
        else k.interestOps(ops);
        int n = timeoutMs < 0 ? s.selector.select() : s.selector.select(Math.max(timeoutMs, 1));
        s.selector.selectedKeys().clear();
        return n > 0;
    }

    static int accept(Memory m, int h, int timeoutMs, int out) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        if (s.server == null) return -EINVAL;
        try {
            for (;;) {
                SocketChannel c = s.server.accept();
                if (c != null) {
                    c.configureBlocking(false);
                    Sock n = new Sock(true);
                    n.client = c;
                    int nh = next.getAndIncrement();
                    socks.put(nh, n);
                    own(nh);
                    putAddress(m, out, c.getRemoteAddress());
                    return nh;
                }
                if (timeoutMs == 0 || !await(s, SelectionKey.OP_ACCEPT, timeoutMs)) return -EAGAIN;
            }
        } catch (IOException | RuntimeException x) {
            return -error(x);
        }
    }

    /** flags: 1 MSG_PEEK. timeoutMs: 0 do not wait (non-blocking), < 0 forever. 0 returned at end of stream. */
    static int recv(Memory m, int h, int buf, int len, int flags, int timeoutMs) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        if (s.datagram != null) return recvfrom(m, h, buf, len, timeoutMs, 0);
        if (s.client == null) return -ENOTCONN;
        try {
            synchronized (s) {
                for (;;) {
                    if (s.pending.hasRemaining()) {
                        int n = Math.min(len, s.pending.remaining());
                        byte[] b = new byte[n];
                        int p = s.pending.position();
                        s.pending.get(b);
                        if ((flags & 1) != 0) s.pending.position(p);
                        m.write(buf, b);
                        return n;
                    }
                    int r = fill(s);
                    if (r < 0) return 0;          // end of stream
                    if (r > 0) continue;
                    if (timeoutMs == 0 || !await(s, SelectionKey.OP_READ, timeoutMs)) return -EAGAIN;
                }
            }
        } catch (IOException | RuntimeException x) {
            return -error(x);
        }
    }

    /** Reads what is there into pending; -1 at end of stream. */
    static int fill(Sock s) throws IOException {
        s.pending.compact();
        try {
            return s.client.read(s.pending);
        } finally {
            s.pending.flip();
        }
    }

    static int send(Memory m, int h, int buf, int len, int flags, int timeoutMs) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        try {
            ByteBuffer b = ByteBuffer.wrap(m.readBytes(buf, len));
            if (s.datagram != null) {
                return s.datagram.write(b);
            }
            if (s.client == null) return -ENOTCONN;
            int sent = 0;
            while (b.hasRemaining()) {
                int n = s.client.write(b);
                sent += n;
                if (n == 0 && b.hasRemaining()) {
                    if (timeoutMs == 0) return sent > 0 ? sent : -EAGAIN;
                    if (!await(s, SelectionKey.OP_WRITE, timeoutMs)) return sent > 0 ? sent : -EAGAIN;
                }
            }
            return sent;
        } catch (IOException | RuntimeException x) {
            return -error(x);
        }
    }

    static int sendto(Memory m, int h, int buf, int len, int addr, int port, int unused) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        if (s.datagram == null) return send(m, h, buf, len, 0, -1);
        try {
            return s.datagram.send(ByteBuffer.wrap(m.readBytes(buf, len)), new InetSocketAddress(ip(addr), port));
        } catch (IOException | RuntimeException x) {
            return -error(x);
        }
    }

    static int recvfrom(Memory m, int h, int buf, int len, int timeoutMs, int out) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        if (s.datagram == null) {
            int n = recv(m, h, buf, len, 0, timeoutMs);
            if (n >= 0 && out != 0 && s.client != null) {
                try { putAddress(m, out, s.client.getRemoteAddress()); } catch (IOException x) { /* none */ }
            }
            return n;
        }
        try {
            ByteBuffer b = ByteBuffer.allocate(len);
            for (;;) {
                SocketAddress from = s.datagram.receive(b);
                if (from != null) {
                    b.flip();
                    byte[] d = new byte[b.remaining()];
                    b.get(d);
                    m.write(buf, d);
                    putAddress(m, out, from);
                    return d.length;
                }
                if (timeoutMs == 0 || !await(s, SelectionKey.OP_READ, timeoutMs)) return -EAGAIN;
            }
        } catch (IOException | RuntimeException x) {
            return -error(x);
        }
    }

    /** how: 0 reading, 1 writing, 2 both */
    static int shutdown(int h, int how) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        if (s.client == null) return -ENOTCONN;
        try {
            if (how == 0 || how == 2) s.client.shutdownInput();
            if (how == 1 || how == 2) s.client.shutdownOutput();
            return 0;
        } catch (IOException | RuntimeException x) {
            return -error(x);
        }
    }

    static void closeQuietly(Sock s) {
        try { if (s.selector != null) s.selector.close(); } catch (IOException x) { /* gone */ }
        try { if (s.client != null) s.client.close(); } catch (IOException x) { /* gone */ }
        try { if (s.server != null) s.server.close(); } catch (IOException x) { /* gone */ }
        try { if (s.datagram != null) s.datagram.close(); } catch (IOException x) { /* gone */ }
    }

    /** The current run opened socket h: it is closed when the run ends, if not before. */
    static void own(int h) {
        Run r = Run.current();
        if (r != null) r.sockets.add(h);
    }

    static int close(int h) {
        Run r = Run.current();
        if (r != null) r.sockets.remove(h);
        Sock s = socks.remove(h);
        if (s == null) return -EBADF;
        closeQuietly(s);
        return 0;
    }

    static int name(Memory m, int h, boolean peer, int out) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        try {
            SocketAddress a;
            if (peer) {
                if (s.client != null) a = s.client.getRemoteAddress();
                else if (s.datagram != null) a = s.datagram.getRemoteAddress();
                else a = null;
                if (a == null) return -ENOTCONN;
            } else {
                SelectableChannel c = s.channel();
                if (c instanceof SocketChannel) a = ((SocketChannel) c).getLocalAddress();
                else if (c instanceof ServerSocketChannel) a = ((ServerSocketChannel) c).getLocalAddress();
                else if (c instanceof DatagramChannel) a = ((DatagramChannel) c).getLocalAddress();
                else a = s.local;
            }
            putAddress(m, out, a);
            return 0;
        } catch (IOException | RuntimeException x) {
            return -error(x);
        }
    }

    /** FIONREAD: the bytes that can be read now */
    static int available(int h) {
        Sock s = socks.get(h);
        if (s == null) return -EBADF;
        try {
            if (s.client != null) {
                synchronized (s) {
                    fill(s);
                    return s.pending.remaining();
                }
            }
            return 0;
        } catch (IOException | RuntimeException x) {
            return -error(x);
        }
    }

    /**
     * select(): r[nr], w[nw] handles (int arrays in memory); on return each entry is
     * replaced by 1 if ready, 0 if not. timeoutMs < 0: forever. Returns the count ready.
     */
    static int select(Memory m, int r, int nr, int w, int nw, int timeoutMs, int unused) {
        try (Selector sel = Selector.open()) {
            List<Object[]> regs = new ArrayList<>();
            int ready = 0;
            int[] flags = new int[nr + nw];
            for (int i = 0; i < nr + nw; i++) {
                boolean read = i < nr;
                int h = m.readInt(read ? r + 4 * i : w + 4 * (i - nr));
                Sock s = socks.get(h);
                if (s == null) return -EBADF;
                SelectableChannel c = s.channel();
                if (c == null) continue;                    // a stream socket not yet used: never ready
                if (read && s.pending.hasRemaining()) {      // read ahead already
                    flags[i] = 1;
                    ready++;
                    continue;
                }
                int ops = read ? (c instanceof ServerSocketChannel ? SelectionKey.OP_ACCEPT : SelectionKey.OP_READ)
                               : (c instanceof ServerSocketChannel ? 0 : SelectionKey.OP_WRITE);
                if (ops == 0) continue;
                SelectionKey k = c.keyFor(sel);
                if (k == null) k = c.register(sel, ops);
                else k.interestOps(k.interestOps() | ops);
                regs.add(new Object[] { k, i, ops });
            }
            if (ready == 0) {
                if (timeoutMs < 0) sel.select();
                else if (timeoutMs == 0) sel.selectNow();
                else sel.select(timeoutMs);
            } else {
                sel.selectNow();
            }
            for (Object[] e : regs) {
                SelectionKey k = (SelectionKey) e[0];
                int i = (Integer) e[1], ops = (Integer) e[2];
                if (k.isValid() && (k.readyOps() & ops) != 0 && flags[i] == 0) {
                    flags[i] = 1;
                    ready++;
                }
            }
            for (int i = 0; i < nr + nw; i++) m.writeI32(i < nr ? r + 4 * i : w + 4 * (i - nr), flags[i]);
            return ready;
        } catch (IOException | RuntimeException x) {
            return -error(x);
        }
    }

    /**
     * mode 0: the host name; 1: arg a name, out "canonical name\naddress\n..." (IPv4);
     * 2: arg an address (four bytes, a.b.c.d text), out its name. Returns the length, or -1.
     */
    static int resolve(Memory m, int mode, int arg, int out, int size) {
        String r;
        try {
            if (mode == 0) {
                try {
                    r = InetAddress.getLocalHost().getHostName();
                } catch (UnknownHostException x) {
                    r = "localhost";
                }
            } else if (mode == 1) {
                String name = m.readCString(arg, StandardCharsets.UTF_8);
                StringBuilder b = new StringBuilder();
                String canonical = null;
                for (InetAddress a : InetAddress.getAllByName(name)) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (canonical == null) canonical = a.getHostName();
                    b.append('\n').append(a.getHostAddress());
                }
                if (canonical == null) return -1;
                r = canonical + b;
            } else {
                String a = m.readCString(arg, StandardCharsets.UTF_8);
                InetAddress i = InetAddress.getByName(a);
                r = i.getCanonicalHostName();
                if (r.equals(a)) return -1;    // no name for it
            }
        } catch (IOException | RuntimeException x) {
            return -1;
        }
        return Host.put(m, out, size, r) == 1 ? r.length() : -1;
    }

    static int error(Throwable x) {
        if (x instanceof ConnectException) return ECONNREFUSED;
        if (x instanceof BindException) {
            String r = x.getMessage();
            return r != null && r.contains("assign") ? EADDRNOTAVAIL : r != null && r.contains("ermission") ? EACCES : EADDRINUSE;
        }
        if (x instanceof SocketTimeoutException) return ETIMEDOUT;
        if (x instanceof NoRouteToHostException) return EHOSTUNREACH;
        if (x instanceof ClosedChannelException) return EBADF;
        if (x instanceof NotYetConnectedException) return ENOTCONN;
        if (x instanceof NotYetBoundException) return EINVAL;
        if (x instanceof AlreadyConnectedException) return EISCONN;
        if (x instanceof UnresolvedAddressException || x instanceof UnknownHostException) return EINVAL;
        if (x instanceof UnsupportedOperationException) return EOPNOTSUPP;
        String r = x.getMessage();
        if (r != null) {
            if (r.contains("reset")) return ECONNRESET;
            if (r.contains("Broken pipe")) return EPIPE;
            if (r.contains("Network is unreachable")) return ENETUNREACH;
            if (r.contains("refused")) return ECONNREFUSED;
            if (r.contains("Permission denied")) return EACCES;
        }
        return x instanceof SocketException || x instanceof IOException ? EIO : EINVAL;
    }
}
