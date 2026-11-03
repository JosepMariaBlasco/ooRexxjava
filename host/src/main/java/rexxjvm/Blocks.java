package rexxjvm;

/**
 * Makes the {@link Block} for a {@link FlatMemory}. This is the version for Java 11 to
 * 21: always an {@link UnsafeBlock}. The jar carries another version of this class in
 * {@code META-INF/versions/22} (source: {@code src/main/java22}), which Java 22 and
 * later load instead, and which makes a mapped block.
 */
final class Blocks {
    private Blocks() {}

    static Block create(long bytes) { return new UnsafeBlock(bytes); }
}
