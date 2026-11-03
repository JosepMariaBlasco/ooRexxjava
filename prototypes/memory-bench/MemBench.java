import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A WebAssembly linear memory on the JVM, with sun.misc.Unsafe and with
 * java.lang.foreign: the same access path as rexxj's FlatMemory (host/.../FlatMemory.java,
 * UnsafeMemory.java, java22/.../MappedMemory.java), standalone, one file, JDK 22+.
 *
 *   java MemBench.java KIND WORKLOAD [SECONDS]
 *     KIND      unsafe | mapped | arena
 *     WORKLOAD  loop | vm
 *   -Dnocheck=true leaves out the memory's own bounds check (FlatMemory's), to see
 *   what the segment's checks cost by themselves.
 *
 * How the code that uses the memory looks: a WebAssembly runtime that translates the
 * module to JVM bytecode (Chicory, now Endive) calls an interface, Memory, for every
 * load and store, with a 32-bit address; one implementation is loaded, so the call is
 * monomorphic and inlined. Each access is one bounds check against a plain int field
 * (the memory's current size) and then one load or store at base + address:
 *   unsafe:  U.getInt(base + addr)                       (a malloc'd block, off-heap)
 *   mapped:  segment.get(JAVA_INT_UNALIGNED, addr)       (a private mapping of a temp
 *            file: lazy zero pages, no restricted method; what rexxj uses)
 *   arena:   segment.get(JAVA_INT_UNALIGNED, addr)       (Arena.ofShared().allocate:
 *            zero-filled eagerly, so only for a small memory like this one)
 * The segment is a final field of the memory object, as rexxj has it.
 *
 * Workloads:
 *   loop  a plain loop over an array in the memory: the JIT can see it whole.
 *   vm    an interpreter for a small register machine whose code, registers and data
 *         are all in the linear memory, in one large method with a switch: what an
 *         interpreter written in C (ooRexx's) looks like once compiled to WebAssembly
 *         and translated to bytecode. Every operation loads and stores through the
 *         interface, from code too large or branchy for the JIT to see a loop in.
 * Each prints millions of operations per second, the median of its runs (one per
 * second after a warm-up). Run the kinds in separate JVMs, alternated (run.sh).
 */
public final class MemBench {

    /** What the translated code calls (Chicory's Memory, reduced). */
    interface Memory {
        int readInt(int addr);
        void writeI32(int addr, int v);
        byte read(int addr);
        void writeByte(int addr, byte v);
    }

    /** FlatMemory: the bounds check, then the block's own access. */
    abstract static class Flat implements Memory {
        static final int SIZE = 64 << 20;     // 64 MB in use; rexxj reserves 2 GB
        private int limit = SIZE;

        /** -Dnocheck=true: without this check (the segment's own still runs; Unsafe has none). */
        static final boolean NOCHECK = Boolean.getBoolean("nocheck");

        private long at(int addr, int n) {
            if (NOCHECK) return addr;
            if ((addr | (limit - n - addr)) < 0) throw new IndexOutOfBoundsException("addr " + addr);
            return addr;
        }
        abstract int getInt(long o);
        abstract void putInt(long o, int v);
        abstract byte getByte(long o);
        abstract void putByte(long o, byte v);

        @Override public final int readInt(int addr) { return getInt(at(addr, 4)); }
        @Override public final void writeI32(int addr, int v) { putInt(at(addr, 4), v); }
        @Override public final byte read(int addr) { return getByte(at(addr, 1)); }
        @Override public final void writeByte(int addr, byte v) { putByte(at(addr, 1), v); }
    }

    static final class UnsafeFlat extends Flat {
        private static final sun.misc.Unsafe U;
        static {
            try {
                Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                U = (sun.misc.Unsafe) f.get(null);
            } catch (ReflectiveOperationException x) {
                throw new ExceptionInInitializerError(x);
            }
        }
        private final long base = U.allocateMemory(SIZE);
        UnsafeFlat() { U.setMemory(base, SIZE, (byte) 0); }
        @Override int getInt(long o) { return U.getInt(base + o); }
        @Override void putInt(long o, int v) { U.putInt(base + o, v); }
        @Override byte getByte(long o) { return U.getByte(base + o); }
        @Override void putByte(long o, byte v) { U.putByte(base + o, v); }
    }

    static final class SegmentFlat extends Flat {
        private static final ValueLayout.OfInt INT_U = ValueLayout.JAVA_INT_UNALIGNED;
        private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
        private final MemorySegment m;
        SegmentFlat(MemorySegment m) { this.m = m; }
        @Override int getInt(long o) { return m.get(INT_U, o); }
        @Override void putInt(long o, int v) { m.set(INT_U, o, v); }
        @Override byte getByte(long o) { return m.get(BYTE, o); }
        @Override void putByte(long o, byte v) { m.set(BYTE, o, v); }

        static SegmentFlat mapped() throws IOException {
            Arena a = Arena.ofShared();
            Path p = Files.createTempFile("membench", ".bin");
            try (FileChannel c = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                Files.delete(p);
                c.write(ByteBuffer.wrap(new byte[1]), SIZE - 1);
                return new SegmentFlat(c.map(FileChannel.MapMode.PRIVATE, 0, SIZE, a));
            } finally {
                Files.deleteIfExists(p);
            }
        }
        static SegmentFlat arena() {
            return new SegmentFlat(Arena.ofShared().allocate(SIZE, 16));
        }
    }

    // ---- workloads ------------------------------------------------------------------------

    static final int DATA = 1 << 20, N = 1 << 16;      // an int[N] at DATA

    /** loop: sum and update an array; one pass is N reads and N writes. */
    static long loop(Memory m, long passes) {
        long ops = 0;
        int s = 0;
        for (long p = 0; p < passes; p++) {
            for (int i = 0; i < N; i++) {
                int a = DATA + 4 * i;
                int v = m.readInt(a);
                s += v;
                m.writeI32(a, v + 1);
            }
            ops += 2L * N;
        }
        sink = s;
        return ops;
    }

    /*
     * vm: a register machine. Code at CODE, 4-byte instructions (op, a, b, c); 16
     * registers at REGS (ints in the memory, as a C interpreter's frame would be in
     * wasm's shadow stack). The program: a loop that walks the array at DATA, reads,
     * hashes and writes back each element, with a call-like detour through memory.
     */
    static final int CODE = 4096, REGS = 2048;
    static final int HALT = 0, LI = 1, ADD = 2, ADDI = 3, LD = 4, ST = 5, XOR = 6, SHL = 7,
        SHR = 8, BLT = 9, JMP = 10, MUL = 11, AND = 12, MOV = 13, LDB = 14, STB = 15;

    static void program(Memory m) {
        int[][] p = {
            { LI, 1, 0, 0 },            // 0: r1 = i = 0
            { LI, 2, N >> 8, 8 },       // 1: r2 = N   (b << c)
            { LI, 3, DATA >> 12, 12 },  // 2: r3 = DATA
            { LI, 7, 0, 0 },            // 3: r7 = acc
            { SHL, 4, 1, 2 },           // 4: r4 = i << 2
            { ADD, 4, 4, 3 },           // 5: r4 = DATA + 4i
            { LD, 5, 4, 0 },            // 6: r5 = mem[r4]
            { MUL, 6, 5, 9 },           // 7: r6 = r5 * r9 (r9 = 0x9E3779B1)
            { SHR, 8, 6, 10 },          // 8: r8 = r6 >>> r10 (16)
            { XOR, 6, 6, 8 },           // 9: r6 ^= r8
            { ADD, 7, 7, 6 },           // 10: acc += r6
            { ADDI, 5, 5, 1 },          // 11: r5 + 1
            { ST, 5, 4, 0 },            // 12: mem[r4] = r5
            { LDB, 11, 4, 0 },          // 13: r11 = byte mem[r4]
            { AND, 11, 11, 12 },        // 14: r11 &= r12 (0x7F)
            { STB, 11, 4, 1 },          // 15: byte mem[r4 + 1] = r11
            { ADDI, 1, 1, 1 },          // 16: i++
            { BLT, 1, 2, 4 },           // 17: if i < N goto 4
            { HALT, 0, 0, 0 },          // 18
        };
        for (int i = 0; i < p.length; i++)
            for (int k = 0; k < 4; k++) m.writeByte(CODE + 4 * i + k, (byte) p[i][k]);
        m.writeI32(REGS + 4 * 9, 0x9E3779B1);
        m.writeI32(REGS + 4 * 10, 16);
        m.writeI32(REGS + 4 * 12, 0x7F);
    }

    static int reg(Memory m, int r) { return m.readInt(REGS + 4 * r); }
    static void reg(Memory m, int r, int v) { m.writeI32(REGS + 4 * r, v); }

    /** One run of the program; the number of instructions executed. */
    static long vm(Memory m) {
        int pc = 0;
        long n = 0;
        for (;;) {
            int at = CODE + 4 * pc;
            int op = m.read(at), a = m.read(at + 1) & 0xFF, b = m.read(at + 2) & 0xFF, c = m.read(at + 3) & 0xFF;
            n++;
            pc++;
            switch (op) {
                case HALT: return n;
                case LI:   reg(m, a, b << c); break;
                case ADD:  reg(m, a, reg(m, b) + reg(m, c)); break;
                case ADDI: reg(m, a, reg(m, b) + c); break;
                case LD:   reg(m, a, m.readInt(reg(m, b) + c)); break;
                case ST:   m.writeI32(reg(m, b) + c, reg(m, a)); break;
                case XOR:  reg(m, a, reg(m, b) ^ reg(m, c)); break;
                case SHL:  reg(m, a, reg(m, b) << c); break;
                case SHR:  reg(m, a, reg(m, b) >>> reg(m, c)); break;
                case BLT:  if (reg(m, a) < reg(m, b)) pc = c; break;
                case JMP:  pc = a; break;
                case MUL:  reg(m, a, reg(m, b) * reg(m, c)); break;
                case AND:  reg(m, a, reg(m, b) & reg(m, c)); break;
                case MOV:  reg(m, a, reg(m, b)); break;
                case LDB:  reg(m, a, m.read(reg(m, b) + c)); break;
                case STB:  m.writeByte(reg(m, b) + c, (byte) reg(m, a)); break;
                default:   throw new IllegalStateException("op " + op);
            }
        }
    }

    static volatile int sink;

    public static void main(String[] args) throws Exception {
        String kind = args.length > 0 ? args[0] : "unsafe", work = args.length > 1 ? args[1] : "vm";
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        Memory m = kind.equals("unsafe") ? new UnsafeFlat()
                 : kind.equals("mapped") ? SegmentFlat.mapped()
                 : kind.equals("arena") ? SegmentFlat.arena()
                 : null;
        if (m == null) throw new IllegalArgumentException("kind: unsafe, mapped or arena");
        if (work.equals("vm")) program(m);
        double[] rates = new double[seconds];
        for (int s = -3; s < seconds; s++) {            // 3 s of warm-up
            long ops = 0, t0 = System.nanoTime(), t;
            do {
                ops += work.equals("vm") ? vm(m) : loop(m, 16);
                t = System.nanoTime();
            } while (t - t0 < 1_000_000_000L);
            if (s >= 0) rates[s] = ops / ((t - t0) / 1e3);
        }
        java.util.Arrays.sort(rates);
        System.out.printf("%s %s: %.1f M ops/s (median of %d; min %.1f, max %.1f)%n",
            kind, work, rates[seconds / 2], seconds, rates[0], rates[seconds - 1]);
    }
}
