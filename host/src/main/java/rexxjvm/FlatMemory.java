package rexxjvm;

import static run.endive.runtime.ConstantEvaluators.computeConstantValue;

import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.types.ActiveDataSegment;
import run.endive.wasm.types.DataSegment;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.PassiveDataSegment;
import java.lang.ref.Cleaner;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntBinaryOperator;
import java.util.function.LongBinaryOperator;

/**
 * The module's linear memory as one flat block outside the Java heap, reserved
 * at its maximum size when it is created.
 *
 * <p>Why: Chicory's {@code ByteArrayMemory} keeps 64 KB pages ({@code byte[][]}),
 * so every access finds its page, checks whether it crosses into the next one,
 * and indexes two arrays; that was about 39% of {@code rexxcps}' time. Here an
 * access is one bounds check and one load. And it allocates every page as soon
 * as the memory grows ({@code byte[65536]} each, in the Java heap), so each
 * thread's C stack, malloc'd from the linear memory and mostly untouched, cost
 * its whole size. Here the block is reserved once at the maximum size, and the
 * operating system gives the pages zero-filled, and only when they are first
 * touched: a subclass, {@link UnsafeMemory} with {@code sun.misc.Unsafe} while the JVM
 * allows it, and on Java 22 and later otherwise {@code MappedMemory}, a private file
 * mapping through {@code java.lang.foreign} (see {@link Blocks}).
 *
 * <p>Semantics as {@code ByteArrayMemory} (Endive 1.2.0, as Chicory 1.7.5's): accesses beyond the
 * current size trap ({@link WasmRuntimeException}); atomics are sequentially
 * consistent; {@code wait} / {@code notify} keep, per address, the waiters and
 * the wake-ups owed to them. Wasm memory is little-endian: on a big-endian
 * machine values are byte-swapped. {@code -Drexxjvm.memory=bytearray} goes back
 * to Chicory's memory (see {@link #create}).
 */
abstract class FlatMemory implements Memory {
    private static final boolean BE = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
    static final Cleaner CLEANER = Cleaner.create();

    private final MemoryLimits limits;
    private final int maxPages;
    private volatile int nPages;
    /**
     * The current size in bytes, a plain field read by every access; nPages, volatile,
     * is the truth. A stale value can only be smaller (memory never shrinks), and an
     * access that fails against it is checked again against nPages before trapping.
     */
    private int limit;
    private final Object growLock = new Object();
    private DataSegment[] dataSegments;
    private final Map<Integer, WaitState> waitStates;

    /**
     * The memory for the module: this one, unless {@code -Drexxjvm.memory=bytearray}
     * asks for Chicory's {@code ByteArrayMemory}.
     */
    static Memory create(MemoryLimits limits) {
        if ("bytearray".equals(System.getProperty("rexxjvm.memory")))
            return new run.endive.runtime.ByteArrayMemory(limits);
        return Blocks.create(limits);
    }

    FlatMemory(MemoryLimits limits) {
        this.limits = limits;
        this.maxPages = Math.min(limits.maximumPages(), RUNTIME_MAX_PAGES);
        this.nPages = limits.initialPages();
        this.limit = nPages * PAGE_SIZE;
        this.waitStates = limits.shared() ? new ConcurrentHashMap<>() : null;
    }

    // ---- size ------------------------------------------------------------------------------

    @Override public int pages() { return nPages; }
    @Override public int initialPages() { return limits.initialPages(); }
    @Override public int maximumPages() { return maxPages; }
    @Override public boolean shared() { return limits.shared(); }

    @Override public int grow(int size) {
        synchronized (growLock) {
            int prev = nPages;
            int n = prev + size;
            if (size < 0 || n > maxPages || n < prev) return -1;
            limit = n * PAGE_SIZE;
            nPages = n;                       // publishes limit too
            return prev;
        }
    }

    // ---- bounds ----------------------------------------------------------------------------

    /** The offset of an access of n bytes at addr; traps if out of bounds. */
    private long at(int addr, int n) {
        // out of bounds if addr < 0 (getAddr gives a negative address for one past 2 GB)
        // or addr + n > limit; no overflow: limit < 2^31 - 64K, n <= 2^31 - 1
        if ((addr | (limit - n - addr)) < 0) return atSlow(addr, n);
        return addr;
    }

    private long atSlow(int addr, int n) {
        int lim = nPages * PAGE_SIZE;         // the limit field may be stale: check the truth
        if (addr < 0 || n > lim || addr > lim - n) throw outOfBounds(addr, n, lim);
        limit = lim;
        return addr;
    }

    /** The offset of an atomic access of n bytes, which must also be aligned. */
    private long atomicAt(int addr, int n) {
        if ((addr & (n - 1)) != 0) throw new WasmRuntimeException("unaligned atomic");
        return at(addr, n);
    }

    private static WasmRuntimeException outOfBounds(int addr, long size, int limit) {
        return new WasmRuntimeException("out of bounds memory access: attempted to access address: "
            + Integer.toUnsignedString(addr) + " but limit is: " + limit + " and size: " + size);
    }

    /** Checks a range [addr, addr + size) of the memory. */
    private long range(int addr, int size) {
        if (size < 0) throw outOfBounds(addr, size, nPages * PAGE_SIZE);
        if (size == 0) {
            int lim = nPages * PAGE_SIZE;
            if (Integer.compareUnsigned(addr, lim) > 0) throw outOfBounds(addr, 0, lim);
            return addr;
        }
        return at(addr, size);
    }

    // ---- plain accesses --------------------------------------------------------------------

    @Override public byte read(int addr) { return getByte(at(addr, 1)); }
    @Override public void writeByte(int addr, byte data) { putByte(at(addr, 1), data); }

    @Override public short readShort(int addr) {
        short v = getShort(at(addr, 2));
        return BE ? Short.reverseBytes(v) : v;
    }
    @Override public long readU16(int addr) { return readShort(addr) & 0xFFFFL; }
    @Override public void writeShort(int addr, short data) {
        putShort(at(addr, 2), BE ? Short.reverseBytes(data) : data);
    }

    @Override public int readInt(int addr) {
        int v = getInt(at(addr, 4));
        return BE ? Integer.reverseBytes(v) : v;
    }
    @Override public void writeI32(int addr, int data) {
        putInt(at(addr, 4), BE ? Integer.reverseBytes(data) : data);
    }

    @Override public long readLong(int addr) {
        long v = getLong(at(addr, 8));
        return BE ? Long.reverseBytes(v) : v;
    }
    @Override public void writeLong(int addr, long data) {
        putLong(at(addr, 8), BE ? Long.reverseBytes(data) : data);
    }

    @Override public float readFloat(int addr) { return Float.intBitsToFloat(readInt(addr)); }
    @Override public long readF32(int addr) { return readInt(addr) & 0xFFFFFFFFL; }
    @Override public void writeF32(int addr, float data) { writeI32(addr, Float.floatToRawIntBits(data)); }
    @Override public double readDouble(int addr) { return Double.longBitsToDouble(readLong(addr)); }
    @Override public long readF64(int addr) { return readLong(addr); }
    @Override public void writeF64(int addr, double data) { writeLong(addr, Double.doubleToRawLongBits(data)); }

    // ---- blocks ----------------------------------------------------------------------------

    @Override public void write(int addr, byte[] data, int offset, int size) {
        if (offset < 0 || size < 0 || offset > data.length - size)
            throw outOfBounds(offset, size, data.length);
        copyFrom(data, offset, range(addr, size), size);
    }

    @Override public byte[] readBytes(int addr, int len) {
        long p = range(addr, len);
        byte[] r = new byte[len];
        copyTo(p, r, 0, len);
        return r;
    }

    @Override public void copy(int dest, int src, int size) {
        long d = range(dest, size), s = range(src, size);
        move(s, d, size);              // overlapping blocks are fine (memmove)
    }

    @Override public void fill(byte value, int fromIndex, int toIndex) {
        set(range(fromIndex, toIndex - fromIndex), toIndex - fromIndex, value);
    }

    @Override public void zero() { set(0, (long) nPages * PAGE_SIZE, (byte) 0); }

    // ---- data segments ---------------------------------------------------------------------

    @Override public void initialize(Instance instance, DataSegment[] dataSegments) {
        initialize(instance, dataSegments, 0);
    }

    @Override public void initialize(Instance instance, DataSegment[] dataSegments, int memoryIndex) {
        this.dataSegments = dataSegments;
        if (dataSegments == null) return;
        for (DataSegment s : dataSegments) {
            if (s instanceof ActiveDataSegment) {
                ActiveDataSegment segment = (ActiveDataSegment) s;
                if (segment.index() != memoryIndex) continue;
                byte[] data = segment.data();
                int offset = (int) computeConstantValue(instance, segment.offsetInstructions())[0];
                write(offset, data, 0, data.length);
            } else if (!(s instanceof PassiveDataSegment)) {
                throw new WasmEngineException("Data segment should be active or passive: " + s);
            }
        }
    }

    @Override public void initPassiveSegment(int segmentId, int dest, int offset, int size) {
        write(dest, dataSegments[segmentId].data(), offset, size);
    }

    @Override public void drop(int segment) { dataSegments[segment] = PassiveDataSegment.EMPTY; }

    // ---- atomics ---------------------------------------------------------------------------

    @Override public void atomicFence() { fullFence(); }

    @Override public int atomicReadInt(int addr) {
        int v = getIntVolatile(atomicAt(addr, 4));
        return BE ? Integer.reverseBytes(v) : v;
    }
    @Override public long atomicReadLong(int addr) {
        long v = getLongVolatile(atomicAt(addr, 8));
        return BE ? Long.reverseBytes(v) : v;
    }
    @Override public short atomicReadShort(int addr) {
        short v = getShortVolatile(atomicAt(addr, 2));
        return BE ? Short.reverseBytes(v) : v;
    }
    @Override public byte atomicReadByte(int addr) { return getByteVolatile(atomicAt(addr, 1)); }

    @Override public void atomicWriteInt(int addr, int v) {
        putIntVolatile(atomicAt(addr, 4), BE ? Integer.reverseBytes(v) : v);
    }
    @Override public void atomicWriteLong(int addr, long v) {
        putLongVolatile(atomicAt(addr, 8), BE ? Long.reverseBytes(v) : v);
    }
    @Override public void atomicWriteShort(int addr, short v) {
        putShortVolatile(atomicAt(addr, 2), BE ? Short.reverseBytes(v) : v);
    }
    @Override public void atomicWriteByte(int addr, byte v) { putByteVolatile(atomicAt(addr, 1), v); }

    /** Read-modify-write of an int: returns the old value. */
    private int rmw(int addr, IntBinaryOperator op, int arg) {
        long p = atomicAt(addr, 4);
        for (;;) {
            int raw = getIntVolatile(p);
            int old = BE ? Integer.reverseBytes(raw) : raw;
            int nv = op.applyAsInt(old, arg);
            if (casInt(p, raw, BE ? Integer.reverseBytes(nv) : nv)) return old;
        }
    }

    private long rmw(int addr, LongBinaryOperator op, long arg) {
        long p = atomicAt(addr, 8);
        for (;;) {
            long raw = getLongVolatile(p);
            long old = BE ? Long.reverseBytes(raw) : raw;
            long nv = op.applyAsLong(old, arg);
            if (casLong(p, raw, BE ? Long.reverseBytes(nv) : nv)) return old;
        }
    }

    /**
     * Read-modify-write of a byte or a short (size 1 or 2, aligned): a CAS of the aligned
     * int that holds it, so that it is atomic with int accesses to the same word. Returns
     * the old value, zero-extended.
     */
    private int rmwSub(int addr, int size, IntBinaryOperator op, int arg) {
        atomicAt(addr, size);
        long p = addr & ~3;
        int bits = size * 8, mask = (1 << bits) - 1;
        int shift = BE ? (4 - size - (addr & 3)) * 8 : (addr & 3) * 8;
        for (;;) {
            int raw = getIntVolatile(p);
            int field = (raw >>> shift) & mask;
            int old = BE && size == 2 ? Short.reverseBytes((short) field) & 0xFFFF : field;
            int nv = op.applyAsInt(old, arg) & mask;
            int nf = BE && size == 2 ? Short.reverseBytes((short) nv) & 0xFFFF : nv;
            if (casInt(p, raw, (raw & ~(mask << shift)) | (nf << shift))) return old;
        }
    }

    private static final IntBinaryOperator ADD = (a, b) -> a + b, AND = (a, b) -> a & b,
        OR = (a, b) -> a | b, XOR = (a, b) -> a ^ b, XCHG = (a, b) -> b;
    private static final LongBinaryOperator LADD = (a, b) -> a + b, LAND = (a, b) -> a & b,
        LOR = (a, b) -> a | b, LXOR = (a, b) -> a ^ b, LXCHG = (a, b) -> b;

    @Override public int atomicAddInt(int addr, int d) {
        return BE ? rmw(addr, ADD, d) : getAndAddInt(atomicAt(addr, 4), d);
    }
    @Override public int atomicXchgInt(int addr, int v) {
        return BE ? rmw(addr, XCHG, v) : getAndSetInt(atomicAt(addr, 4), v);
    }
    @Override public int atomicAndInt(int addr, int m) { return rmw(addr, AND, m); }
    @Override public int atomicOrInt(int addr, int m) { return rmw(addr, OR, m); }
    @Override public int atomicXorInt(int addr, int m) { return rmw(addr, XOR, m); }
    @Override public int atomicCmpxchgInt(int addr, int expected, int replacement) {
        long p = atomicAt(addr, 4);
        int e = BE ? Integer.reverseBytes(expected) : expected;
        int r = BE ? Integer.reverseBytes(replacement) : replacement;
        for (;;) {
            int raw = getIntVolatile(p);
            if (raw != e) return BE ? Integer.reverseBytes(raw) : raw;
            if (casInt(p, e, r)) return expected;
        }
    }

    @Override public long atomicAddLong(int addr, long d) {
        return BE ? rmw(addr, LADD, d) : getAndAddLong(atomicAt(addr, 8), d);
    }
    @Override public long atomicXchgLong(int addr, long v) {
        return BE ? rmw(addr, LXCHG, v) : getAndSetLong(atomicAt(addr, 8), v);
    }
    @Override public long atomicAndLong(int addr, long m) { return rmw(addr, LAND, m); }
    @Override public long atomicOrLong(int addr, long m) { return rmw(addr, LOR, m); }
    @Override public long atomicXorLong(int addr, long m) { return rmw(addr, LXOR, m); }
    @Override public long atomicCmpxchgLong(int addr, long expected, long replacement) {
        long p = atomicAt(addr, 8);
        long e = BE ? Long.reverseBytes(expected) : expected;
        long r = BE ? Long.reverseBytes(replacement) : replacement;
        for (;;) {
            long raw = getLongVolatile(p);
            if (raw != e) return BE ? Long.reverseBytes(raw) : raw;
            if (casLong(p, e, r)) return expected;
        }
    }

    @Override public short atomicAddShort(int addr, short d) { return (short) rmwSub(addr, 2, ADD, d); }
    @Override public short atomicAndShort(int addr, short m) { return (short) rmwSub(addr, 2, AND, m); }
    @Override public short atomicOrShort(int addr, short m) { return (short) rmwSub(addr, 2, OR, m); }
    @Override public short atomicXorShort(int addr, short m) { return (short) rmwSub(addr, 2, XOR, m); }
    @Override public short atomicXchgShort(int addr, short v) { return (short) rmwSub(addr, 2, XCHG, v); }
    @Override public short atomicCmpxchgShort(int addr, short expected, short replacement) {
        int e = expected & 0xFFFF, r = replacement & 0xFFFF;
        return (short) rmwSub(addr, 2, (old, x) -> old == e ? r : old, 0);
    }

    @Override public byte atomicAddByte(int addr, byte d) { return (byte) rmwSub(addr, 1, ADD, d); }
    @Override public byte atomicAndByte(int addr, byte m) { return (byte) rmwSub(addr, 1, AND, m); }
    @Override public byte atomicOrByte(int addr, byte m) { return (byte) rmwSub(addr, 1, OR, m); }
    @Override public byte atomicXorByte(int addr, byte m) { return (byte) rmwSub(addr, 1, XOR, m); }
    @Override public byte atomicXchgByte(int addr, byte v) { return (byte) rmwSub(addr, 1, XCHG, v); }
    @Override public byte atomicCmpxchgByte(int addr, byte expected, byte replacement) {
        int e = expected & 0xFF, r = replacement & 0xFF;
        return (byte) rmwSub(addr, 1, (old, x) -> old == e ? r : old, 0);
    }

    // ---- wait / notify ---------------------------------------------------------------------
    // As ByteArrayMemory: per address, the threads waiting and the wake-ups owed to them.

    private static final class WaitState {
        int waiterCount;                      // threads waiting
        int pendingWakeups;                   // of them, how many were told to wake up
    }

    @Override public Object lock(int address) { throw new UnsupportedOperationException(); }

    private interface Condition { boolean holds(); }

    private int waitOn(int address, Condition condition, long timeout) {
        if (!shared()) throw new WasmEngineException("Attempt to wait on a non-shared memory, not supported.");
        long deadline = timeout < 0 ? Long.MAX_VALUE : System.nanoTime() + timeout;
        WaitState state = waitStates.computeIfAbsent(address, k -> new WaitState());
        synchronized (state) {
            if (!condition.holds()) return 1;             // not-equal
            state.waiterCount++;
            try {
                while (state.pendingWakeups == 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return 2;         // timed out
                    try {
                        state.wait(Math.max(remaining / 1_000_000L, 0), Math.max((int) (remaining % 1_000_000L), 0));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new run.endive.runtime.WasmInterruptedException("Thread interrupted");
                    }
                }
                return 0;                                 // woken
            } finally {
                if (state.pendingWakeups > 0) state.pendingWakeups--;
                state.waiterCount--;
            }
        }
    }

    @Override public int waitOn(int address, int expected, long timeout) {
        return waitOn(address, () -> atomicReadInt(address) == expected, timeout);
    }

    @Override public int waitOn(int address, long expected, long timeout) {
        return waitOn(address, () -> atomicReadLong(address) == expected, timeout);
    }

    @Override public int notify(int address, int maxThreads) {
        if (!shared()) return 0;
        WaitState state = waitStates.get(address);
        if (state == null) return 0;
        synchronized (state) {
            int waiting = state.waiterCount - state.pendingWakeups;
            if (waiting == 0) return 0;
            int toWake = maxThreads < 0 ? waiting : Math.min(waiting, maxThreads);
            state.pendingWakeups += toWake;
            state.notifyAll();
            return toWake;
        }
    }

    // ---- the bytes: one subclass per kind of block (Blocks) ---------------------------------
    // The block is reserved at its full size, zero-filled, outside the Java heap, and
    // addressed by offset; no bounds checks below this line, values in the machine's
    // byte order. Atomic and volatile accesses need offsets aligned to their size. One
    // subclass is loaded per run, so HotSpot inlines these into the accesses above, with
    // the block's address a field of this same object (a separate block object cost an
    // extra dependent load per access).

    /** The size to reserve, in bytes: the maximum, so that the block never moves. */
    final long reservation() { return (long) maxPages * PAGE_SIZE; }

    abstract byte getByte(long o);
    abstract void putByte(long o, byte v);
    abstract short getShort(long o);
    abstract void putShort(long o, short v);
    abstract int getInt(long o);
    abstract void putInt(long o, int v);
    abstract long getLong(long o);
    abstract void putLong(long o, long v);

    abstract byte getByteVolatile(long o);
    abstract void putByteVolatile(long o, byte v);
    abstract short getShortVolatile(long o);
    abstract void putShortVolatile(long o, short v);
    abstract int getIntVolatile(long o);
    abstract void putIntVolatile(long o, int v);
    abstract long getLongVolatile(long o);
    abstract void putLongVolatile(long o, long v);

    abstract boolean casInt(long o, int expected, int replacement);
    abstract boolean casLong(long o, long expected, long replacement);
    abstract int getAndAddInt(long o, int d);
    abstract int getAndSetInt(long o, int v);
    abstract long getAndAddLong(long o, long d);
    abstract long getAndSetLong(long o, long v);
    abstract void fullFence();

    /** Copies n bytes of an array into the block at o. */
    abstract void copyFrom(byte[] src, int srcOffset, long o, int n);
    /** Copies n bytes of the block at o into an array. */
    abstract void copyTo(long o, byte[] dst, int dstOffset, int n);
    /** Copies n bytes within the block; overlapping ranges are fine (memmove). */
    abstract void move(long from, long to, long n);
    /** Sets n bytes of the block at o to v. */
    abstract void set(long o, long n, byte v);
}
