package repro;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import java.nio.file.Path;

/** Runs a WASI command: Run [--compile] module.wasm hostDir [args...]; hostDir is preopened as "/". */
public final class Run {
    public static void main(String[] a) {
        int i = 0;
        boolean compile = a[i].equals("--compile");
        if (compile) i++;
        var module = Parser.parse(Path.of(a[i++]));
        var dir = Path.of(a[i++]).toAbsolutePath();
        var args = new java.util.ArrayList<String>();
        args.add("repro");
        while (i < a.length) args.add(a[i++]);
        var opts = WasiOptions.builder().inheritSystem().withArguments(args)
                .withDirectory("/", dir).build();
        try (var wasi = WasiPreview1.builder().withOptions(opts).build()) {
            var b = Instance.builder(module)
                    .withImportValues(ImportValues.builder().addFunction(wasi.toHostFunctions()).build());
            if (compile) b.withMachineFactory(MachineFactoryCompiler::compile);
            b.build();   // runs _start
        } catch (WasiExitException e) {
            System.exit(e.exitCode());
        }
    }
}
