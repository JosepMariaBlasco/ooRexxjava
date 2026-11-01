package rexxjvm;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
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

/** Runs a wasm32-wasip1-threads command module on Chicory, with wasi-threads (thread-spawn). */
public final class RunWasm {
    static WasmModule module;
    static ImportValues imports;
    static Function<Instance, Machine> machine;   // null: interpreter
    static final AtomicInteger nextTid = new AtomicInteger(1);

    public static void main(String[] args) throws Exception {
        boolean interp = Boolean.getBoolean("rexxjvm.interp");
        module = Parser.parse(new File(args[0]));
        List<String> argv = new ArrayList<>(Arrays.asList(args));
        argv.set(0, new File(args[0]).getName());
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
                Thread t = new Thread(() -> {
                    try {
                        Instance ti = newInstance();
                        ti.export("wasi_thread_start").apply(tid, startArg);
                    } catch (WasiExitException x) {
                        System.out.flush(); System.exit(x.exitCode());
                    } catch (Throwable x) {
                        System.err.println("thread " + tid + ": " + x);
                        x.printStackTrace();
                        System.exit(70);
                    }
                }, "wasm-" + tid);
                t.setDaemon(true);
                t.start();
                return new long[] { tid };
            });

        imports = ImportValues.builder()
            .addFunction(wasi.toHostFunctions())
            .addFunction(spawn)
            .addMemory(new ImportMemory(mi.module(), mi.name(), memory))
            .build();
        machine = interp ? null : MachineFactoryCompiler.compile(module);

        int rc = 0;
        try {
            Instance main = newInstance();
            main.export("_start").apply();
        } catch (WasiExitException x) {
            rc = x.exitCode();
        }
        System.out.flush();
        System.exit(rc);
    }

    static Instance newInstance() {
        Instance.Builder b = Instance.builder(module).withImportValues(imports).withStart(false);
        if (machine != null) b = b.withMachineFactory(machine);
        return b.build();
    }
}
