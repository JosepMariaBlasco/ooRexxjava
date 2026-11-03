# Several runs in one JVM

`MultiRun.java` runs the module many times in one JVM as hosted runs
(`host/.../Run.java`), each with its own arguments, environment, current
directory and standard streams (captured), and checks:

- 12 runs one after another, each with its own output (and how long each
  takes: the first pays for the JIT);
- the exit code; the environment, the current directory and a command's
  output; standard input and standard error;
- 20 runs on 4 threads at once, none mixed with another;
- a run with two threads looping for ever, ended from outside (`Run.end`):
  its exit code, and no threads left;
- SIGINT given to a run (`Run.signal`) as a HALT;
- no wasm threads left, and every run's memory collected.

It is in package `rexxjvm` (`Run` is not public yet):

```bash
D=/home/claude/work/dist                    # a build (build.sh)
env -u JAVA_TOOL_OPTIONS javac -cp $D/rexxj.jar -d out tests/multirun/MultiRun.java
env -u JAVA_TOOL_OPTIONS java -Xss16m -Drexxjvm.executable=$D/rexx -Drexxjvm.home=$D \
    -cp out:$D/rexxj.jar rexxjvm.MultiRun
```

Result (03/11/2026, compiled machine, Java 21, 2 cores): `9 checks, 0
failures`; the 12 runs took 744 ms the first, 60–119 ms the others; the run
ended from outside stopped in 2 ms with no threads left; 13 memories, all
collected.
