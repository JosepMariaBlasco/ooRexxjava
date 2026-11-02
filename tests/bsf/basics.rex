/* BSF4ooRexx on the JVM: objects, static members, collections, exceptions, arrays */
.local~checks = 0; .local~failed = 0
sb = .bsf~new("java.lang.StringBuilder")
sb~append("x")~append(42)
call check sb~toString, "x42", "StringBuilder"
call check .bsf~bsf.getStaticValue("java.lang.Integer", "MAX_VALUE"), 2147483647, "static field"
list = .bsf~new("java.util.ArrayList")
do w over "pera manzana kiwi uva"~makeArray(" ")
  list~add(w)
end
call check list~size, 4, "ArrayList~size"
call check .bsf~new("java.lang.String", "ñandú")~length, 5, "UTF-8 string to Java"
arr = bsf.createJavaArray("int.class", 3)
arr[1] = 10; arr[2] = 20; arr[3] = 12
call check arr[1] + arr[2] + arr[3], 42, "int[]"
signal on syntax name javaError
.bsf~new("java.lang.Integer", "no-number")
call check "no condition", "condition", "Java exception"
javaError:
  if condition("C") = "SYNTAX" then call check condition("O")~code, "40.900", "Java exception as SYNTAX"
  /* a callback: Java's Collections.sort calls a Rexx comparator */
  cmp = BsfCreateRexxProxy(.ByLength~new, , "java.util.Comparator")
  .bsf~bsf.import("java.util.Collections")~sort(list, cmp)
  call check list~toString, "[uva, pera, kiwi, manzana]", "Rexx comparator called by Java"
  say "basics:" .checks "checks," .failed "failed"
  exit .failed

check: procedure
  use arg got, expected, what
  .local~checks = .checks + 1
  if got \== expected then do
    .local~failed = .failed + 1
    say "FAIL" what": got ["got"], expected ["expected"]"
  end
  return

::class ByLength
::method compare
  use arg a, b
  return sign(a~length - b~length)

::requires "BSF.CLS"
