package rexxjvm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * The linear memory as the operating system reserves it, lazily zero-filled: an
 * anonymous private {@code mmap} on Linux and macOS, {@code VirtualAlloc} on Windows,
 * made a segment of its size with {@code reinterpret}, whose arena frees it
 * ({@code munmap}, {@code VirtualFree}). Restricted methods (the {@link Linker} and
 * {@code reinterpret}): only when the JVM grants this code native access
 * ({@code --enable-native-access=ALL-UNNAMED}, which the launchers pass on Java 22 and
 * later), as Maurizio Cimadamore advised on panama-dev (11/11/2026). The pages cost
 * nothing until touched, as with {@code Unsafe}'s {@code malloc} and unlike
 * {@code Arena.allocate}, which writes every one ({@link MappedMemory}).
 */
final class OsReservation {
    private OsReservation() {}

    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final boolean MAC =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    /** Whether this code may use restricted methods without a warning. */
    static boolean allowed() {
        return OsReservation.class.getModule().isNativeAccessEnabled();
    }

    /** {@code size} bytes of zeros, freed when {@code arena} closes. */
    static MemorySegment reserve(long size, Arena arena) {
        try {
            return WINDOWS ? virtualAlloc(size, arena) : mmap(size, arena);
        } catch (RuntimeException | Error x) {
            throw x;
        } catch (Throwable x) {
            throw new IllegalStateException("rexxj: cannot reserve the module's memory: " + x, x);
        }
    }

    private static MemorySegment mmap(long size, Arena arena) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        MethodHandle mmap = linker.downcallHandle(libc.find("mmap").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                  ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                  ValueLayout.JAVA_LONG));
        MethodHandle munmap = linker.downcallHandle(libc.find("munmap").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        final int PROT_READ = 1, PROT_WRITE = 2, MAP_PRIVATE = 2;
        int anonymous = MAC ? 0x1000 : 0x20;                // MAP_ANONYMOUS (MAP_ANON on macOS)
        int noReserve = MAC ? 0 : 0x4000;                   // Linux's MAP_NORESERVE: no swap set aside
        MemorySegment p = (MemorySegment) mmap.invokeExact(MemorySegment.NULL, size,
            PROT_READ | PROT_WRITE, MAP_PRIVATE | anonymous | noReserve, -1, 0L);
        if (p.address() == -1L) throw new IllegalStateException("rexxj: mmap of " + size + " bytes failed");
        long at = p.address();
        return p.reinterpret(size, arena, s -> {
            try {
                int r = (int) munmap.invokeExact(MemorySegment.ofAddress(at), size);
            } catch (Throwable ignored) {
                // nothing to do: the process is going, or the memory stays mapped
            }
        });
    }

    private static MemorySegment virtualAlloc(long size, Arena arena) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        MethodHandle alloc = linker.downcallHandle(kernel32.find("VirtualAlloc").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                  ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MethodHandle free = linker.downcallHandle(kernel32.find("VirtualFree").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                  ValueLayout.JAVA_INT));
        final int MEM_COMMIT = 0x1000, MEM_RESERVE = 0x2000, MEM_RELEASE = 0x8000, PAGE_READWRITE = 4;
        MemorySegment p = (MemorySegment) alloc.invokeExact(MemorySegment.NULL, size,
            MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
        if (p.address() == 0L) throw new IllegalStateException("rexxj: VirtualAlloc of " + size + " bytes failed");
        long at = p.address();
        return p.reinterpret(size, arena, s -> {
            try {
                int r = (int) free.invokeExact(MemorySegment.ofAddress(at), 0L, MEM_RELEASE);
            } catch (Throwable ignored) {
                // nothing to do
            }
        });
    }
}
