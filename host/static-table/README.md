# call_indirect resolved at build time (04/11/2026)

Endive translates every `call_indirect` of a signature into a static method
`call_indirect_N` of the machine class. On each call it checks for an
interruption, looks the table up (`Instance.table(i)`, then
`TableInstance.requiredRef` and `instance` for the entry), and dispatches on
the function number it found: a `switch` on its top bits, and a
`lookupswitch` in an `Indirect_N_k.apply` (`host/chicory-patch/` keeps those
under HotSpot's 8,000-byte limit). In `rexxcps` every C++ virtual call goes
through it: 18% of the time in a steady state, half of it in the table's
lookup (async-profiler, 26 s run, README "Speed").

`rexx.wasm`'s table never changes: it has one table, neither imported nor
exported, filled by active element segments at constant offsets, and none of
its functions has `table.set`, `table.grow`, `table.fill`, `table.copy` or
`table.init`. So the function behind each table index is known when the module
is translated. `StaticTable.java`, run by `host/pom.xml`'s `embedded` profile in
the `process-classes` phase (after Endive's build-time compiler, Java's
source-file mode, the project's classpath for Endive's parser and ASM):

- checks those conditions with Endive's own parser (every instruction of every
  function); if one fails it says which, and leaves the classes as they are;
- reads, from Endive's generated `call_indirect_N` and `Indirect_N_k`, the
  method each function number of signature N calls (so the signature check is
  Endive's own: a function is a case of N only if Endive dispatches it there);
- writes `RexxModuleMachineTable`: for each N, a `call_indirect_N` with the
  same descriptor that, for table 0, checks for an interruption as Endive does
  and switches on the table index straight to the function (a `lookupswitch`;
  with more than 500 cases, a `tableswitch` on blocks of 512 indices first,
  each block a method of its own, all under 8,000 bytes);
- renames Endive's `call_indirect_N` to `call_indirect_N$endive`, unchanged, and
  gives the machine a `call_indirect_N` that only passes its arguments on to the
  dispatcher's (HotSpot inlines it). Whatever the dispatcher has no case for (an
  empty entry, a wrong signature, an index past the table, another table) goes
  to `call_indirect_N$endive`, so errors are Endive's own, word for word
  (`tests/static-table/`).

It assumes that nothing changes the table from outside the module either: the
host could, through `Instance.table(0)`, and rexxj does not.
`-Dstatic.table.skip=true` builds without it.

Result (Java 21, 2 cores, five rounds each, medians): `rexxcps` over 26 s 3.80
to 4.17 M clauses/s (+10%); as it is (4 s, with the JVM's warm-up) 2.59 to 2.63
M. With `rexx.wasm`: 33 signatures, 2,940 table entries (`dist-test`'s: 3,396).
Endive's interruption check stays (2% of the time): the functions check at
their own calls and loops, and removing it would change what Endive promises.
