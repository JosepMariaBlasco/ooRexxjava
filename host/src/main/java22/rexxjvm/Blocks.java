package rexxjvm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.util.Locale;

/**
 * Makes the {@link Block} for a {@link FlatMemory}: the version for Java 22 and later
 * (the jar's {@code META-INF/versions/22}), which makes a {@link MappedBlock}, so that
 * {@code sun.misc.Unsafe} is never touched and JDK 24+ print no warning.
 *
 * <p>An {@link UnsafeBlock} instead on Windows (a mapped block needs a file that can be
 * deleted while open, and copy-on-write views there commit their whole size), with
 * {@code -Drexxjvm.memory=unsafe}, or if the mapping fails (then with a note on
 * standard error).
 */
final class Blocks {
    private Blocks() {}

    static Block create(long bytes) {
        if ("unsafe".equals(System.getProperty("rexxjvm.memory"))
                || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
            return new UnsafeBlock(bytes);
        if ("unsafe-mapped".equals(System.getProperty("rexxjvm.memory"))) {
            try { MappedBlock mb = new MappedBlock(bytes); return new UnsafeBlock(mb.address(), mb); }
            catch (IOException x) { throw new RuntimeException(x); }
        }
        if (MappedBlock.STATIC) { MappedBlock.staticSize = bytes; return new MappedBlock(bytes, Arena.global(), true); }
        try {
            return new MappedBlock(bytes);
        } catch (IOException | RuntimeException x) {
            System.err.println("rexxj: cannot map the module's memory (" + x + "); using sun.misc.Unsafe");
            return new UnsafeBlock(bytes);
        }
    }
}
