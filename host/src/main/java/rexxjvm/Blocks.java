package rexxjvm;

import run.endive.wasm.types.MemoryLimits;

/**
 * Makes the {@link FlatMemory}. This is the version for Java 11 to
 * 21: always an {@link UnsafeMemory}. The jar carries another version of this class in
 * {@code META-INF/versions/22} (source: {@code src/main/java22}), which Java 22 and
 * later load instead: it falls back to a mapped block ({@code java.lang.foreign}) when
 * the JVM refuses {@code Unsafe}, or when asked for ({@code -Drexxjvm.memory=mapped}).
 */
final class Blocks {
    private Blocks() {}

    static FlatMemory create(MemoryLimits limits) {
        if ("mapped".equals(System.getProperty("rexxjvm.memory")))
            System.err.println("rexxj: -Drexxjvm.memory=mapped needs Java 22 or later; using sun.misc.Unsafe");
        return new UnsafeMemory(limits);
    }
}
