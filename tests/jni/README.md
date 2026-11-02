# The mini-JNI's test

`jnitest.c` uses the mini-JNI (`compat/orx_jni.c`, with the launcher's side in
`host/src/main/java/rexxjvm/Jni.java`) from C, the way `BSF4ooRexx.cc` will:
every family of `JNIEnv` functions against `jnitest/JniTarget.java`.

- the `JavaVM`: `GetEnv` before and after attaching, a version that is too
  high, `GetJavaVM`, `JNI_GetCreatedJavaVMs`, `DetachCurrentThread`;
- classes: `FindClass` (also an array class, and a missing one:
  `NoClassDefFoundError`), `GetSuperclass`, `IsAssignableFrom`,
  `GetObjectClass`, `IsInstanceOf`;
- constructors and methods: `NewObject` / `NewObjectA`, `Call<T>Method` with
  every kind of argument through the `va_list`, through a `jvalue[]` and
  through a `va_list` passed on (`...V`), static methods, a nonvirtual call
  to an overridden method, a private method, an inherited one, and a public
  method of a class Java does not let us reach (an iterator of
  `java.util.ImmutableCollections`: called through `Iterator`);
- exceptions: thrown by Java, `ThrowNew`, `Throw`, a call on `NULL`, missing
  methods and fields, `ExceptionOccurred` / `ExceptionCheck` /
  `ExceptionClear`;
- fields: every kind, get and set, private, static, `System.out`;
- strings: standard UTF-8 in (four-byte forms too), modified UTF-8 out
  (supplementary characters as surrogates, NUL as `C0 80`), `GetStringChars`,
  the regions and their bounds;
- arrays: every primitive kind, elements (copy back, `JNI_ABORT`, an empty
  array), regions and their bounds, `GetPrimitiveArrayCritical`, object arrays
  (`ArrayStoreException`, index out of bounds);
- references: global, weak, `NewLocalRef`, frames (`PushLocalFrame` /
  `PopLocalFrame`, the result kept, the rest freed);
- monitors (reentrant; exiting a monitor not held fails);
- 8 threads at once, each attaching, making 10,000 calls and detaching;
- the cost of a call.

It is a tool of `dist-test/`'s `rexx.wasm` (`build-test.sh`): `rexxj
--orx-wasm-jnitest`. `dist-test/jnitest` runs it with the test class on the
`CLASSPATH`:

```bash
env -u JAVA_TOOL_OPTIONS /home/claude/work/dist-test/jnitest
```

The exit code is the number of failed checks.

Result (02/11/2026, compiled machine, Java 21, 2 cores): `100 checks, 0
failed`; 8 threads × 2,000 iterations of 5 JNI calls in 0.7 s;
`CallIntMethod` 0.5 µs per call; `NewStringUTF` + `DeleteLocalRef` 0.6 µs per
pair.
