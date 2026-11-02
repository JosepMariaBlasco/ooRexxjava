package rexxjvm;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.CompiledModule;
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryImport;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Runs a wasm32-wasip1-threads command module on Chicory, with wasi-threads (thread-spawn).
 *
 * <p>{@code RunWasm module.wasm args...} parses the module and translates it to JVM
 * bytecode at start-up. A jar built with the {@code embedded} Maven profile carries
 * {@code rexx.wasm} already translated ({@code rexxjvm.RexxModule}); there,
 * {@code RunWasm args...} runs it, and every argument goes to the module.
 */
public final class RunWasm {
    static WasmModule module;
    static ImportValues imports;
    static Function<Instance, Machine> machine;   // null: interpreter
    static final AtomicInteger nextTid = new AtomicInteger(1);
    /** The module file given on the command line; null: the embedded one. */
    static String wasmFile;
    /**
     * Java stack for every thread that runs wasm code. Chicory's frames live on it, so
     * deep Rexx recursion needs far more than Java's default (the suite's CALL group
     * overflows 64 MB and passes with 256 MB). Reserved, not committed: unused stack
     * costs no memory. {@code -Drexxjvm.stack=<bytes>} overrides it.
     */
    static final long STACK = Long.getLong("rexxjvm.stack", 256L << 20);

    /**
     * The embedded mode ({@link Rexx}): the module hosted by a Java program. No
     * {@code System.exit} from the module's threads, and thread-spawn can hand a new
     * pthread to the Java thread that asked for it ({@link Rexx#handover}).
     */
    static volatile boolean embedded;

    public static void main(String[] args) throws Exception {
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try { run(args); } catch (Throwable x) { failure[0] = x; }
        }, "wasm-main", STACK);
        t.start();
        t.join();
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
        if (failure[0] != null) throw (Error) failure[0];
    }

    static void run(String[] args) throws Exception {
        List<String> argv = load(args);
        Host.installSignals(argv);
        link(argv);
        int rc = start();
        System.out.flush();
        System.exit(rc);
    }

    /**
     * Chooses the module: {@code args[0]} if it is a {@code .wasm} file (or there is no
     * module translated at build time), else the embedded one. Returns the module's argv.
     */
    static List<String> load(String[] args) throws Exception {
        CompiledModule embeddedModule = Boolean.getBoolean("rexxjvm.interp") ? null : embeddedModule();
        List<String> argv = new ArrayList<>(Arrays.asList(args));
        if (embeddedModule != null && !(args.length > 0 && args[0].endsWith(".wasm"))) {
            module = embeddedModule.wasmModule();
            machine = embeddedModule.machineFactory();
            argv.add(0, "rexx");
        } else {
            if (args.length == 0) throw new IllegalArgumentException("no module: give a .wasm file");
            wasmFile = new File(args[0]).getAbsolutePath();
            module = Parser.parse(new File(args[0]));
            machine = null;
            argv.set(0, new File(args[0]).getName());
        }
        return argv;
    }

    /** WASI, the shared memory, thread-spawn and the launcher's imports. */
    static void link(List<String> argv) {
        boolean interp = Boolean.getBoolean("rexxjvm.interp");
        WasiOptions.Builder ob = WasiOptions.builder().inheritSystem().withArguments(argv)
            .withDirectory("/", Path.of("/"));
        for (Map.Entry<String, String> e : System.getenv().entrySet()) if (!e.getKey().equals("PWD")) ob.withEnvironment(e.getKey(), e.getValue());
        ob.withEnvironment("PWD", System.getProperty("user.dir"));
        WasiPreview1 wasi = WasiPreview1.builder().withOptions(ob.build()).build();

        // the shared memory the module imports (wasi-threads modules import "env" "memory")
        MemoryImport mi = module.importSection().stream()
            .filter(i -> i instanceof MemoryImport).map(i -> (MemoryImport) i).findFirst().orElseThrow();
        Memory memory = new ByteArrayMemory(mi.limits());

        HostFunction spawn = new HostFunction("wasi", "thread-spawn",
            List.of(ValType.I32), List.of(ValType.I32), (inst, a) -> {
                int tid = nextTid.getAndIncrement();
                int startArg = (int) a[0];
                if (embedded && Rexx.handover(tid, startArg)) return new long[] { tid };
                Thread t = new Thread(null, () -> {
                    try {
                        Instance ti = newInstance();
                        ti.export("wasi_thread_start").apply(tid, startArg);
                    } catch (WasiExitException x) {
                        if (embedded) { Rexx.exited(x.exitCode()); return; }
                        System.out.flush(); System.exit(x.exitCode());
                    } catch (Throwable x) {
                        System.err.println("thread " + tid + ": " + x);
                        x.printStackTrace();
                        if (!embedded) System.exit(70);
                    }
                }, "wasm-" + tid, STACK);
                t.setDaemon(true);
                t.start();
                return new long[] { tid };
            });

        imports = ImportValues.builder()
            .addFunction(wasi.toHostFunctions())
            .addFunction(spawn)
            .addFunction(Host.functions().toArray(new HostFunction[0]))
            .addFunction(RxApi.functions().toArray(new HostFunction[0]))
            .addFunction(FileSys.functions().toArray(new HostFunction[0]))
            .addFunction(Net.functions().toArray(new HostFunction[0]))
            .addFunction(Rexx.functions().toArray(new HostFunction[0]))
            .addMemory(new ImportMemory(mi.module(), mi.name(), memory))
            .build();
        if (machine == null && !interp) machine = MachineFactoryCompiler.compile(module);
    }

    /** Runs {@code _start} on the current thread; returns the exit code. */
    static int start() {
        try {
            Instance main = newInstance();
            main.export("_start").apply();
            return 0;
        } catch (WasiExitException x) {
            return x.exitCode();
        }
    }

    /** The module translated at build time, if this jar carries one. */
    static CompiledModule embeddedModule() {
        try {
            return (CompiledModule) Class.forName("rexxjvm.RexxModule").getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException x) {
            return null;
        } catch (ReflectiveOperationException x) {
            throw new IllegalStateException(x);
        }
    }

    static Instance newInstance() {
        Instance.Builder b = Instance.builder(module).withImportValues(imports).withStart(false);
        if (machine != null) b = b.withMachineFactory(machine);
        return b.build();
    }
}
