/* classpath.rex: which Greeter this run's CLASSPATH gives, and its static counter */
g = bsf.import("Greeter")
say g~hello g~hello
::requires "BSF.CLS"
