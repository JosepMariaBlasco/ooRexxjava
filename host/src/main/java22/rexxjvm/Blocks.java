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
 * <p>Without {@code Unsafe} ({@code --sun-misc-unsafe-memory-access=deny}, the default
 * of a later JDK), a {@link MappedMemory}: a {@code MemorySegment}, about a third slower.
 * Its segment is the OS's own reservation ({@link OsReservation}: {@code mmap},
 * {@code VirtualAlloc}) when this code has native access
 * ({@code --enable-native-access=ALL-UNNAMED}, which the launchers pass on Java 22 and
 * later); else a private mapping of a temp file, not on Windows (a mapped block there
 * needs a file that can be deleted while open, and copy-on-write views commit their
 * whole size), where there is then only {@code Unsafe}. {@code -Drexxjvm.memory=}
 * {@code unsafe}, {@code native} or {@code mapped} (the temp file) asks for one.
 */
final class Blocks {
    private Blocks() {}

    static FlatMemory create(MemoryLimits limits) {
        String asked = System.getProperty("rexxjvm.memory", "");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        if (asked.equals("native")) {
            if (OsReservation.allowed()) return segment(limits, true);
            System.err.println("rexxj: -Drexxjvm.memory=native needs native access (--enable-native-access=ALL-UNNAMED)");
        }
        if (asked.equals("mapped") && !windows) return segment(limits, false);
        if (asked.equals("unsafe")) return UnsafeMemory.of(limits);
        try {
            return UnsafeMemory.of(limits);
        } catch (UnsupportedOperationException | LinkageError x) {
            // denied: allocateMemory throws (or UnsafeMemory's initialization failed)
            if (OsReservation.allowed()) return segment(limits, true);
            if (windows) throw new IllegalStateException(
                "rexxj: sun.misc.Unsafe is denied and there is no native access for the memory"
                + " (--enable-native-access=ALL-UNNAMED)", x);
            return segment(limits, false);
        }
    }

    private static FlatMemory segment(MemoryLimits limits, boolean os) {
        try {
            return MappedMemory.of(limits, os);
        } catch (IOException x) {
            throw new IllegalStateException("rexxj: cannot map the module's memory: " + x, x);
        }
    }
}
