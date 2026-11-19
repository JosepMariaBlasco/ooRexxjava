package rexxjvm;

import run.endive.runtime.Memory;

/**
 * The plain loads and stores of the translated module with WebAssembly's effective
 * address computed in long: the i32 address zero-extended plus the offset, as the
 * specification defines it, instead of Endive's getAddr in int. Same names and
 * descriptors as the helpers of Endive's Shaded class (memoryReadInt(int base, int
 * offset, Memory)...), so that a helper's body can be replaced by a call to the one
 * here (prototypes/long-addresses, an experiment; prototypes/memory-bench, "Addresses in
 * long"; JDK-8397094). The memory must be a FlatMemory (rexxj's).
 */
public final class LongAddr {
    private LongAddr() {}

    private static long ea(int base, int offset) {
        return Integer.toUnsignedLong(base) + Integer.toUnsignedLong(offset);
    }

    public static byte memoryReadByte(int base, int offset, Memory m) {
        return ((FlatMemory) m).readL(ea(base, offset));
    }
    public static short memoryReadShort(int base, int offset, Memory m) {
        return ((FlatMemory) m).readShortL(ea(base, offset));
    }
    public static int memoryReadInt(int base, int offset, Memory m) {
        return ((FlatMemory) m).readIntL(ea(base, offset));
    }
    public static long memoryReadLong(int base, int offset, Memory m) {
        return ((FlatMemory) m).readLongL(ea(base, offset));
    }
    public static float memoryReadFloat(int base, int offset, Memory m) {
        return Float.intBitsToFloat(((FlatMemory) m).readIntL(ea(base, offset)));
    }
    public static double memoryReadDouble(int base, int offset, Memory m) {
        return Double.longBitsToDouble(((FlatMemory) m).readLongL(ea(base, offset)));
    }

    public static void memoryWriteByte(int base, byte value, int offset, Memory m) {
        ((FlatMemory) m).writeByteL(ea(base, offset), value);
    }
    public static void memoryWriteShort(int base, short value, int offset, Memory m) {
        ((FlatMemory) m).writeShortL(ea(base, offset), value);
    }
    public static void memoryWriteInt(int base, int value, int offset, Memory m) {
        ((FlatMemory) m).writeI32L(ea(base, offset), value);
    }
    public static void memoryWriteLong(int base, long value, int offset, Memory m) {
        ((FlatMemory) m).writeLongL(ea(base, offset), value);
    }
    public static void memoryWriteFloat(int base, float value, int offset, Memory m) {
        ((FlatMemory) m).writeI32L(ea(base, offset), Float.floatToRawIntBits(value));
    }
    public static void memoryWriteDouble(int base, double value, int offset, Memory m) {
        ((FlatMemory) m).writeLongL(ea(base, offset), Double.doubleToRawLongBits(value));
    }
}
