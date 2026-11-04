package rexxjvm;
import run.endive.runtime.Memory;
import run.endive.wasm.types.MemoryLimits;
public class MemCheckTest {
    static int fails = 0;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }
    static boolean traps(Runnable r) { try { r.run(); return false; } catch (RuntimeException x) { return true; } }
    public static void main(String[] a) {
        Memory small = FlatMemory.create(new MemoryLimits(1, 4));          // a small module: strict
        Memory big = FlatMemory.create(new MemoryLimits(2, 32768));        // rexx.wasm's 2 GB
        System.out.println("small: " + small.getClass().getName() + ", big: " + big.getClass().getName());
        boolean strictAsked = "strict".equals(System.getProperty("rexxjvm.memcheck"));
        ok(!small.getClass().getName().endsWith("Relaxed"), "small module is strict");
        ok(big.getClass().getName().endsWith("Relaxed") != strictAsked, "big module relaxed unless strict asked");
        // within size: both work
        small.writeI32(65532, 42); ok(small.readInt(65532) == 42, "small: last int in size");
        big.writeLong(131064, 7L); ok(big.readLong(131064) == 7L, "big: last long in size");
        // past the size
        ok(traps(() -> small.readInt(65533)), "small: int straddling the size traps");
        ok(traps(() -> small.readInt(65536)), "small: int past the size traps");
        ok(traps(() -> small.read(4 * 65536)), "small: past the maximum traps");
        boolean r = !strictAsked;
        ok(traps(() -> big.readInt(131072)) != r, "big: int past the size " + (r ? "reads the reserve" : "traps"));
        ok(traps(() -> big.readLong(Integer.MAX_VALUE - 7)) != r, "big: long ending at 2^31 - 1");
        ok(traps(() -> big.readLong(Integer.MAX_VALUE)) != r, "big: long at 2^31 - 1 (the guard)");
        ok(traps(() -> big.read(-1)), "big: address past 2 GB (negative) traps");
        ok(traps(() -> big.readInt(Integer.MIN_VALUE)), "big: 2^31 traps");
        // blocks and atomics stay strict
        ok(traps(() -> big.readBytes(131072, 4)), "big: block past the size traps");
        ok(traps(() -> big.atomicReadInt(131072)), "big: atomic past the size traps");
        ok(traps(() -> big.write(131070, new byte[4], 0, 4)), "big: block straddling the size traps");
        // growing keeps the bytes and the new pages work
        ok(big.grow(1) == 2, "big: grow");
        big.writeI32(131072, 5); ok(big.readInt(131072) == 5, "big: int in the new page");
        ok(small.grow(1) == 1 && !traps(() -> small.readInt(65536)), "small: grow, then the int works");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
