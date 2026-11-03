/* sysout.rex: Java's System.out and System.err, written from Rexx through BSF4ooRexx */
sys = bsf.loadClass("java.lang.System")
say "rexx: before"
sys~out~println("java: System.out")
sys~err~println("java: System.err")
say "rexx: after"
::requires "BSF.CLS"
