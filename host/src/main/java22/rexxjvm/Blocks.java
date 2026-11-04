package rexxjvm;

import java.io.IOException;
import run.endive.wasm.types.MemoryLimits;
import java.util.Locale;

/**
 * Makes the {@link FlatMemory}: the version for Java 22 and later
 * (the jar's {@code META-INF/versions/22}).
 *
 * <p>An {@link UnsafeMemory} while the JVM allows {@code sun.misc.Unsafe}'s memory
 * access: it is the fastest (rexxcps 2.8-3.0 M against 1.7-2.0 M with a
 * {@link MappedMemory} on JDK 25: a {@code MemorySegment} checks every access, and the
 * JIT cannot take those checks out of the module's huge methods). JDK 24 and later
 * print a warning on its first use (JEP 498), unless the JVM was started with
 * {@code --sun-misc-unsafe-memory-access=allow}.
 *
 * <p>A {@link MappedMemory}, with no {@code Unsafe} at all, when the JVM refuses it
 * ({@code --sun-misc-unsafe-memory-access=deny}, the default of a later JDK), or when
 * asked for with {@code -Drexxjvm.memory=mapped}. Not on Windows: a mapped block needs a
 * file that can be deleted while open, and copy-on-write views there commit their whole
 * size. {@code -Drexxjvm.memory=unsafe} insists on {@code Unsafe}.
 */
final class Blocks {
    private Blocks() {}

    static FlatMemory create(MemoryLimits limits) {
        String asked = System.getProperty("rexxjvm.memory", "");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        if (asked.equals("mapped") && !windows) return mapped(limits);
        if (asked.equals("unsafe") || windows) return UnsafeMemory.of(limits);
        try {
            return UnsafeMemory.of(limits);
        } catch (UnsupportedOperationException | LinkageError x) {
            // denied: allocateMemory throws (or UnsafeMemory's initialization failed)
            return mapped(limits);
        }
    }

    private static FlatMemory mapped(MemoryLimits limits) {
        try {
            return MappedMemory.of(limits);
        } catch (IOException x) {
            throw new IllegalStateException("rexxj: cannot map the module's memory: " + x, x);
        }
    }
}
