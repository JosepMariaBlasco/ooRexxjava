package rexxjvm;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@link Block} with {@code java.lang.foreign} (Java 22+), and no restricted method,
 * so neither {@code Unsafe}'s warning (JEP 498) nor native access's.
 *
 * <p>The reservation must stay lazy: each thread's C stack is malloc'd from the linear
 * memory and mostly untouched, and the block is reserved at the maximum size. But
 * {@code Arena.allocate} zero-fills what it allocates, touching every page (4 GB: 35 s,
 * 4.2 GB resident, JDK 25). So the block is a private (copy-on-write) mapping of a temp
 * file, extended to the size and deleted at once: the mapping costs nothing until
 * touched, untouched pages read as zeros (the file's hole), and written pages become
 * the process's own memory, never written back to the file. Mapping {@code /dev/zero}
 * would be simpler, but {@code FileChannel.map} refuses it.
 */
final class MappedBlock extends Block {
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfShort SHORT_U = ValueLayout.JAVA_SHORT_UNALIGNED;
    private static final ValueLayout.OfInt INT_U = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong LONG_U = ValueLayout.JAVA_LONG_UNALIGNED;
    // aligned, for volatile and atomic accesses; coordinates (MemorySegment, long offset)
    private static final VarHandle VBYTE = ValueLayout.JAVA_BYTE.varHandle();
    private static final VarHandle VSHORT = ValueLayout.JAVA_SHORT.varHandle();
    private static final VarHandle VINT = ValueLayout.JAVA_INT.varHandle();
    private static final VarHandle VLONG = ValueLayout.JAVA_LONG.varHandle();

    private final Arena arena;
    private final MemorySegment m;

    // EXPERIMENT: one segment per JVM in a static final field, so the JIT sees a constant
    static final boolean STATIC = Boolean.getBoolean("rexxjvm.static");
    static volatile long staticSize;
    static final class H {
        static final MemorySegment G;
        static {
            try { G = new MappedBlock(staticSize, Arena.global()).m; } catch (IOException x) { throw new ExceptionInInitializerError(x); }
        }
    }
    MappedBlock(long bytes, Arena a, boolean unused) { this.arena = a; this.m = H.G; }

    MappedBlock(long bytes) throws IOException {
        this(bytes, pick());
    }
    static Arena pick() {
        String k = System.getProperty("rexxjvm.arena", "shared");
        return k.equals("global") ? Arena.global() : k.equals("auto") ? Arena.ofAuto() : Arena.ofShared();
    }
    MappedBlock(long bytes, Arena a) throws IOException {
        long size = Math.max(bytes, 4096);
        Path p = Files.createTempFile("rexxj-memory", ".bin");
        try (FileChannel c = FileChannel.open(p, READ, WRITE)) {
            Files.delete(p);
            c.write(ByteBuffer.wrap(new byte[1]), size - 1);   // the file's size, a hole up to it
            this.m = c.map(FileChannel.MapMode.PRIVATE, 0, size, a);
        } catch (IOException | RuntimeException x) {
            try { a.close(); } catch (UnsupportedOperationException y) { }
            throw x;
        } finally {
            Files.deleteIfExists(p);
        }
        this.arena = a;
    }

    long address() { return m.address(); }

    private MemorySegment seg() { return STATIC ? H.G : m; }

    @Override byte getByte(long o) { return seg().get(BYTE, o); }
    @Override void putByte(long o, byte v) { seg().set(BYTE, o, v); }
    @Override short getShort(long o) { return seg().get(SHORT_U, o); }
    @Override void putShort(long o, short v) { seg().set(SHORT_U, o, v); }
    @Override int getInt(long o) { return seg().get(INT_U, o); }
    @Override void putInt(long o, int v) { seg().set(INT_U, o, v); }
    @Override long getLong(long o) { return seg().get(LONG_U, o); }
    @Override void putLong(long o, long v) { seg().set(LONG_U, o, v); }

    @Override byte getByteVolatile(long o) { return (byte) VBYTE.getVolatile(m, o); }
    @Override void putByteVolatile(long o, byte v) { VBYTE.setVolatile(m, o, v); }
    @Override short getShortVolatile(long o) { return (short) VSHORT.getVolatile(m, o); }
    @Override void putShortVolatile(long o, short v) { VSHORT.setVolatile(m, o, v); }
    @Override int getIntVolatile(long o) { return (int) VINT.getVolatile(m, o); }
    @Override void putIntVolatile(long o, int v) { VINT.setVolatile(m, o, v); }
    @Override long getLongVolatile(long o) { return (long) VLONG.getVolatile(m, o); }
    @Override void putLongVolatile(long o, long v) { VLONG.setVolatile(m, o, v); }

    @Override boolean casInt(long o, int e, int r) { return VINT.compareAndSet(m, o, e, r); }
    @Override boolean casLong(long o, long e, long r) { return VLONG.compareAndSet(m, o, e, r); }
    @Override int getAndAddInt(long o, int d) { return (int) VINT.getAndAdd(m, o, d); }
    @Override int getAndSetInt(long o, int v) { return (int) VINT.getAndSet(m, o, v); }
    @Override long getAndAddLong(long o, long d) { return (long) VLONG.getAndAdd(m, o, d); }
    @Override long getAndSetLong(long o, long v) { return (long) VLONG.getAndSet(m, o, v); }
    @Override void fullFence() { VarHandle.fullFence(); }

    @Override void copyFrom(byte[] src, int srcOffset, long o, int n) {
        MemorySegment.copy(src, srcOffset, m, BYTE, o, n);
    }
    @Override void copyTo(long o, byte[] dst, int dstOffset, int n) {
        MemorySegment.copy(m, BYTE, o, dst, dstOffset, n);
    }
    @Override void copy(long from, long to, long n) { MemorySegment.copy(m, from, m, to, n); }
    @Override void fill(long o, long n, byte v) { m.asSlice(o, n).fill(v); }

    @Override Runnable releaser() {
        Arena a = arena;
        return () -> { try { a.close(); } catch (UnsupportedOperationException x) { } };
    }
    @Override String kind() { return "mapped"; }
}
