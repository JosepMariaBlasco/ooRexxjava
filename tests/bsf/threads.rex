/* BSF4ooRexx on the JVM: Java threads that are not running Rexx call Rexx objects
   (as a Swing listener would): each runs the Rexx code on its own Java thread */
n = 4
threads = .array~new
do i = 1 to n
  t = .bsf~new("java.lang.Thread", BsfCreateRexxProxy(.Job~new(i), , "java.lang.Runnable"), "java-"i)
  t~bsf.invoke("start")
  threads~append(t)
end
do t over threads
  t~join
end
names = .Job~seen~makeArray~sort~toString("L", " ")
expected = "java-1 java-2 java-3 java-4"
if names == expected then say "threads: ok ("names")"
else say "threads: FAIL, got ["names"], expected ["expected"]"
exit names \== expected

::class Job
::attribute seen class
::method init class
  expose seen
  seen = .set~new
::method init
  expose i
  use arg i
::method run
  name = bsf.loadClass("java.lang.Thread")~currentThread~getName
  .Job~seen~put(name)

::requires "BSF.CLS"
