package rexxjvm;

import java.lang.reflect.Field;
import run.endive.wasm.types.MemoryLimits;
import sun.misc.Unsafe;

/**
 * A {@link FlatMemory} with {@code sun.misc.Unsafe}: one {@code malloc} of the full size,
 * which the C library serves with {@code mmap}, so the operating system gives the pages
 * zero-filled, and only when they are first touched. The default on every Java, while
 * the JVM allows it (see {@link Blocks}); JDK 24 and later print a warning on its first
 * use unless started with {@code --sun-misc-unsafe-memory-access=allow}.
 */
final class UnsafeMemory extends FlatMemory {
    private static final Unsafe U;
    private static final long BYTE_BASE;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
            BYTE_BASE = U.arrayBaseOffset(byte[].class);
        } catch (ReflectiveOperationException x) {
            throw new ExceptionInInitializerError(x);
        }
    }

    private final long base;

    UnsafeMemory(MemoryLimits limits) {
        super(limits);
        long bytes = reservation();
        long b = U.allocateMemory(Math.max(bytes, 8));
        this.base = b;
        CLEANER.register(this, () -> U.freeMemory(b));
        // A block this large comes from mmap, zero-filled. Check a few spots anyway: if
        // an allocator handed back used memory, clear it all (correct, but no longer lazy).
        if (bytes > 0 && (U.getLong(b) != 0 || U.getLong(b + bytes / 2) != 0 || U.getLong(b + bytes - 8) != 0))
            U.setMemory(b, bytes, (byte) 0);
    }

    @Override byte getByte(long o) { return U.getByte(base + o); }
    @Override void putByte(long o, byte v) { U.putByte(base + o, v); }
    @Override short getShort(long o) { return U.getShort(base + o); }
    @Override void putShort(long o, short v) { U.putShort(base + o, v); }
    @Override int getInt(long o) { return U.getInt(base + o); }
    @Override void putInt(long o, int v) { U.putInt(base + o, v); }
    @Override long getLong(long o) { return U.getLong(base + o); }
    @Override void putLong(long o, long v) { U.putLong(base + o, v); }

    @Override byte getByteVolatile(long o) { return U.getByteVolatile(null, base + o); }
    @Override void putByteVolatile(long o, byte v) { U.putByteVolatile(null, base + o, v); }
    @Override short getShortVolatile(long o) { return U.getShortVolatile(null, base + o); }
    @Override void putShortVolatile(long o, short v) { U.putShortVolatile(null, base + o, v); }
    @Override int getIntVolatile(long o) { return U.getIntVolatile(null, base + o); }
    @Override void putIntVolatile(long o, int v) { U.putIntVolatile(null, base + o, v); }
    @Override long getLongVolatile(long o) { return U.getLongVolatile(null, base + o); }
    @Override void putLongVolatile(long o, long v) { U.putLongVolatile(null, base + o, v); }

    @Override boolean casInt(long o, int e, int r) { return U.compareAndSwapInt(null, base + o, e, r); }
    @Override boolean casLong(long o, long e, long r) { return U.compareAndSwapLong(null, base + o, e, r); }
    @Override int getAndAddInt(long o, int d) { return U.getAndAddInt(null, base + o, d); }
    @Override int getAndSetInt(long o, int v) { return U.getAndSetInt(null, base + o, v); }
    @Override long getAndAddLong(long o, long d) { return U.getAndAddLong(null, base + o, d); }
    @Override long getAndSetLong(long o, long v) { return U.getAndSetLong(null, base + o, v); }
    @Override void fullFence() { U.fullFence(); }

    @Override void copyFrom(byte[] src, int srcOffset, long o, int n) {
        U.copyMemory(src, BYTE_BASE + srcOffset, null, base + o, n);
    }
    @Override void copyTo(long o, byte[] dst, int dstOffset, int n) {
        U.copyMemory(null, base + o, dst, BYTE_BASE + dstOffset, n);
    }
    @Override void move(long from, long to, long n) { U.copyMemory(base + from, base + to, n); }
    @Override void set(long o, long n, byte v) { U.setMemory(base + o, n, v); }

}
