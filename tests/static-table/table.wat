;; A module whose table never changes, with every way a call_indirect can go wrong:
;; tests/static-table/README.md. Assembled into table.wasm (wat2wasm or Binaryen's wasm-as).
(module
  (type $ii (func (param i32) (result i32)))
  (type $v (func (result i32)))
  (type $lj (func (param i64 f64) (result i64)))
  (table 6 6 funcref)
  ;; 0 and 5 empty; 1, 2 of type $ii; 3 of type $v; 4 of type $lj
  (elem (i32.const 1) $inc $dbl $seven $mix)
  (func $inc (type $ii) (i32.add (local.get 0) (i32.const 1)))
  (func $dbl (type $ii) (i32.mul (local.get 0) (i32.const 2)))
  (func $seven (type $v) (i32.const 7))
  (func $mix (type $lj) (i64.add (local.get 0) (i64.trunc_f64_s (local.get 1))))
  (func (export "call_ii") (param $i i32) (param $x i32) (result i32)
    (call_indirect (type $ii) (local.get $x) (local.get $i)))
  (func (export "call_v") (param $i i32) (result i32)
    (call_indirect (type $v) (local.get $i)))
  (func (export "call_lj") (param $i i32) (param $a i64) (param $b f64) (result i64)
    (call_indirect (type $lj) (local.get $a) (local.get $b) (local.get $i)))
  (memory 1 1))
