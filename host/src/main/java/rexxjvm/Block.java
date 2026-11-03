package rexxjvm;

/**
 * The raw storage under {@link FlatMemory}: one block of bytes outside the Java heap,
 * reserved at its full size and zero-filled, addressed by offset. No bounds checks here
 * (FlatMemory does them); values in the machine's byte order.
 *
 * <p>Two implementations, one per run (see {@link Blocks}): {@link UnsafeBlock}, with
 * {@code sun.misc.Unsafe}, for Java 11 to 21, and {@code MappedBlock}, with
 * {@code java.lang.foreign}, for Java 22 and later (in the jar's
 * {@code META-INF/versions/22}), since JDK 24 warns about {@code Unsafe}'s memory
 * access (JEP 498) and later JDKs will refuse it. An abstract class, not an interface,
 * so that HotSpot sees a single loaded subclass and inlines its methods.
 *
 * <p>Atomic and volatile accesses need offsets aligned to their size (FlatMemory checks
 * it); plain accesses do not.
 */
abstract class Block {
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
    abstract void copy(long from, long to, long n);
    abstract void fill(long o, long n, byte v);

    /** Gives the block back; called by FlatMemory's cleaner, once nothing can use it. */
    abstract Runnable releaser();
    /** For diagnostics: which kind of block this is. */
    abstract String kind();
}
