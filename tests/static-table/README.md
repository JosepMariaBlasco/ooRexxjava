# call_indirect: the dispatch resolved at build time against Endive's

`table.wat` (assembled into `table.wasm` with `wat2wasm` or Binaryen's
`wasm-as`) has a table that never changes, with entries of three signatures,
two empty ones, and three exported functions that make a `call_indirect` of
each signature with any index. `rexxjvm/TableTest.java` calls them with every
index from -1 to 7, and with 2^31 - 1 and -2^31: the right function, an empty
entry ("uninitialized element"), a wrong signature ("indirect call type
mismatch"), an index past the table ("undefined element").

```bash
tests/static-table/run.sh
```

builds `rexxj.jar` from `table.wasm` twice, with `host/static-table/` and
without it (`-Dstatic.table.skip=true`), runs the test with each, and checks
that both print `expected.txt`: same results, same exceptions, same messages.
It leaves `host/target` with that jar; `build.sh` builds it again.
