# Chicory's call_indirect dispatch, in smaller methods (02/11/2026)

*Since 03/11/2026 the project runs on Endive 1.2.0, Chicory's continuation, whose
compiler has the same two constants in the same method: the patch applies
unchanged. `scripts/endive-compiler.sh` (formerly `chicory-compiler.sh`) makes
`run.endive:compiler:1.2.0-orx1` from the official `compiler-1.2.0.jar`;
`host/pom.xml`'s property is `endive.compiler.version`. Reported to Endive as
bytecodealliance/endive#273 (its maintainer means to fix it, with or on top of
its #270, method splitting). What follows was written for Chicory 1.7.5.*

HotSpot never JIT-compiles a method over 8,000 bytes of bytecode
(`-XX:+DontCompileHugeMethods`, the default). Chicory 1.7.5's compiler
(`Compiler.compileCallIndirect`) emits, per function signature, one
`call_indirect_N` with a `lookupswitch` over every function of that signature
in the table when there are at most 1,024 (`MAX_MACHINE_CALL_METHODS`, a
`private static final`), else `Indirect_N_<start>.apply` methods over blocks of
4,096 function indices. With `rexx.wasm`, `call_indirect_2` (8,409 bytes),
`call_indirect_7` (10,918), `Indirect_1_0.apply` (11,500) and
`Indirect_5_0.apply` (9,393) exceed the limit, run interpreted for ever, and are
hot: every C++ virtual call goes through them (JFR, `rexxcps` without the flag:
58% of the samples in interpreted code, those four at the top).

`PatchCompiler.java` rewrites, with ASM, the two constants in
`compileCallIndirect` of Chicory's `Compiler.class` (1,024 -> 128, 4,096 ->
512), and fails unless it finds exactly those two (a newer Chicory needs
looking at). `scripts/chicory-compiler.sh`, which `build.sh` and
`build-test.sh` run first, makes from the official `compiler-1.7.5.jar` (not
touched) a `compiler-1.7.5-orx1.jar` with the patched class and the same
dependencies, and installs it in the local Maven repository; `host/pom.xml`
uses that version (`chicory.compiler.version`) both for the plugin that
translates `rexx.wasm` at build time and in `rexxj.jar` (for a `.wasm` given at
run time). Nothing is done if it is already installed.

When Chicory makes those sizes depend on the bytecode, or configurable (to be
reported, Josep Maria's decision), this goes.

Results of the experiment (2 cores, Java 21, `FlatMemory`, no JVM flag): `rexxcps` 1.87-1.89 M
clauses/s, against 0.63 M unpatched (1.19 M unpatched with
`-XX:-DontCompileHugeMethods`; 1.75 M patched with it). Start-up the same
(`hello.rex` 1.0-1.1 s). Methods still over 8,000 bytes: `func_3002`
(`MemoryObject::createImage`, never run by `rexxj`), `func_482`, `func_5835`
(`printf_core`), and `call_dispatch_0`/`_4096` (`Machine.call`, calls from
outside the module). ooRexx's suite: 398/405 with two groups at a time
(`results/suite-jvm-r13346-indirect128.csv`); the six that failed pass alone
(`-indirect128-alone.csv`): timing under load; `ProcessInvocation` over 300 s
as always.
