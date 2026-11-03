package rexxjvm;

import run.endive.runtime.HostFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasm.types.ValType;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/**
 * Layer 0 of Java from Rexx: the object table and the launcher's side of the mini-JNI
 * ({@code compat/orx_jni.c}), import module {@code jni}.
 *
 * <p>C code in the module sees a real {@code JNIEnv}; each of its functions calls one
 * of the functions below. A {@code jobject} (and {@code jclass}, {@code jstring},
 * arrays...) is a handle into {@link #objs}: 0 is {@code null}. As in JNI, a handle is
 * a local, global or weak global reference. Local references belong to the thread that
 * created them and live in its current frame: a frame is pushed by
 * {@code PushLocalFrame}, by {@code AttachCurrentThread}, and (later) around every
 * call from Java into the module; it frees its references when it is popped. A thread
 * that uses JNI without attaching has a base frame that is never popped, as an
 * attached thread that never detaches would. {@code jmethodID} and {@code jfieldID}
 * are indexes into {@link #methods} and {@link #fields}, never freed (as in JNI, valid
 * while the class is loaded).
 *
 * <p>Every function catches what Java throws and leaves it pending for the thread
 * ({@code ExceptionCheck}, {@code ExceptionOccurred}), returning 0, as JNI does.
 *
 * <p>Arguments of the {@code Call...Method} family arrive as a {@code jvalue[]} or as
 * the C {@code va_list} itself: in wasm32 a {@code va_list} is a pointer to the
 * arguments, each promoted (int for boolean, byte, char and short; double for float)
 * and aligned to its size; the method's descriptor says how to read them.
 */
final class Jni {
    private Jni() {}

    // ---- the object table ------------------------------------------------------------

    static final byte LOCAL = 1, GLOBAL = 2, WEAK = 3;   // JNIInvalidRefType 0, local 1, global 2, weak 3
    private static Object[] objs = new Object[1024];
    private static byte[] kinds = new byte[1024];
    private static int[] owner = new int[1024];           // a local reference's frame id
    private static int[] free = new int[1024];
    private static int nfree, top = 1;                    // handle 0 is null
    private static int nextFrameId = 1;

    private static synchronized int alloc(Object o, byte kind, int frameId) {
        int h;
        if (nfree > 0) h = free[--nfree];
        else {
            if (top == objs.length) {
                int n = objs.length * 2;
                objs = Arrays.copyOf(objs, n);
                kinds = Arrays.copyOf(kinds, n);
                owner = Arrays.copyOf(owner, n);
            }
            h = top++;
        }
        objs[h] = kind == WEAK ? new WeakReference<>(o) : o;
        kinds[h] = kind;
        owner[h] = frameId;
        return h;
    }

    private static synchronized void release(int h) {
        if (h <= 0 || h >= top || kinds[h] == 0) return;
        objs[h] = null;
        kinds[h] = 0;
        owner[h] = 0;
        if (nfree == free.length) free = Arrays.copyOf(free, free.length * 2);
        free[nfree++] = h;
    }

    /** Frees a local reference only if it still belongs to the frame (it may have been deleted and reused). */
    private static synchronized void releaseIfOwned(int h, int frameId) {
        if (h > 0 && h < top && kinds[h] == LOCAL && owner[h] == frameId) release(h);
    }

    /** The object a handle refers to; null for 0, a cleared weak reference, or a stale handle. */
    static synchronized Object get(int h) {
        if (h <= 0 || h >= top) return null;
        Object o = objs[h];
        return kinds[h] == WEAK ? ((WeakReference<?>) o).get() : o;
    }

    static synchronized int refType(int h) {
        return h <= 0 || h >= top ? 0 : kinds[h];
    }

    /** A new local reference in the current thread's frame; 0 for null. */
    static int local(Object o) {
        if (o == null) return 0;
        Frame f = state().frame();
        int h = alloc(o, LOCAL, f.id);
        f.add(h);
        return h;
    }

    static int global(Object o) {
        return o == null ? 0 : alloc(o, GLOBAL, runOwner());
    }

    /** A global or weak reference belongs to the run that made it: -(run id); 0 if none. */
    static int runOwner() {
        Run r = Run.current();
        return r == null ? 0 : -r.id;
    }

    /** A run ended: the global and weak references it made are released. */
    static synchronized void releaseRun(int runId) {
        for (int h = 1; h < top; h++)
            if ((kinds[h] == GLOBAL || kinds[h] == WEAK) && owner[h] == -runId) release(h);
    }

    // ---- threads: frames and the pending exception -------------------------------------

    static final class Frame {
        final int id;
        int[] hs = new int[16];
        int n;
        Frame() { synchronized (Jni.class) { id = nextFrameId++; } }
        void add(int h) {
            if (n == hs.length) hs = Arrays.copyOf(hs, n * 2);
            hs[n++] = h;
        }
        void pop() {
            for (int i = 0; i < n; i++) releaseIfOwned(hs[i], id);
            n = 0;
        }
    }

    static final class ThreadState {
        final ArrayDeque<Frame> frames = new ArrayDeque<>();
        Throwable pending;
        int attached;          // AttachCurrentThread nesting: its frame is the bottom one pushed
        ThreadState() { frames.push(new Frame()); }   // the base frame
        Frame frame() { return frames.peek(); }
    }

    private static final ThreadLocal<ThreadState> STATE = ThreadLocal.withInitial(ThreadState::new);

    static ThreadState state() { return STATE.get(); }

    /**
     * A native method is called ({@link Natives}): a frame for its local references, and
     * the thread counts as attached (GetEnv says JNI_OK) while it runs. Returns the depth
     * to give {@link #exitNative}.
     */
    static int enterNative() {
        ThreadState s = state();
        s.attached++;
        s.frames.push(new Frame());
        return s.frames.size();
    }

    /** The native method returned: its frames are popped; returns (and clears) the exception it left pending. */
    static Throwable exitNative(int depth) {
        ThreadState s = state();
        while (s.frames.size() >= depth && s.frames.size() > 1) s.frames.pop().pop();
        if (s.attached > 0) s.attached--;
        Throwable t = s.pending;
        s.pending = null;
        return t;
    }

    private static int fail(Throwable x) {
        if (x instanceof InvocationTargetException && x.getCause() != null) x = x.getCause();
        state().pending = x;
        return 0;
    }

    // ---- classes, methods, fields ------------------------------------------------------

    static final class MethodEntry {
        final Executable e;
        final char[] args;     // the arguments' kinds: Z B C S I J F D L
        final char ret;        // V, or a kind
        final boolean isStatic;
        MethodHandle special;  // for CallNonvirtual..., made on first use
        MethodEntry(Executable e, boolean isStatic) {
            this.e = e;
            Class<?>[] p = e.getParameterTypes();
            args = new char[p.length];
            for (int i = 0; i < p.length; i++) args[i] = kind(p[i]);
            ret = e instanceof Method ? kind(((Method) e).getReturnType()) : 'V';
            this.isStatic = isStatic;
        }
    }

    static final class FieldEntry {
        final Field f;
        final char kind;
        FieldEntry(Field f) { this.f = f; kind = kind(f.getType()); }
    }

    private static final List<MethodEntry> methods = new ArrayList<>(Collections.singletonList(null));   // id 0: none
    private static final List<FieldEntry> fields = new ArrayList<>(Collections.singletonList(null));
    /** Per class, "name+descriptor+static" to the id: GetMethodID is often called again for the same method. */
    private static final Map<Class<?>, Map<String, Integer>> methodIds = new WeakHashMap<>();
    private static final Map<Class<?>, Map<String, Integer>> fieldIds = new WeakHashMap<>();

    static synchronized MethodEntry method(int id) { return methods.get(id); }
    static synchronized FieldEntry field(int id) { return fields.get(id); }

    static char kind(Class<?> c) {
        if (c == void.class) return 'V';
        if (c == boolean.class) return 'Z';
        if (c == byte.class) return 'B';
        if (c == char.class) return 'C';
        if (c == short.class) return 'S';
        if (c == int.class) return 'I';
        if (c == long.class) return 'J';
        if (c == float.class) return 'F';
        if (c == double.class) return 'D';
        return 'L';
    }

    static String descriptor(Class<?> c) {
        if (c.isPrimitive()) return String.valueOf(kind(c));
        if (c.isArray()) return c.getName().replace('.', '/');
        return "L" + c.getName().replace('.', '/') + ";";
    }

    static String descriptor(Executable e) {
        StringBuilder b = new StringBuilder("(");
        for (Class<?> p : e.getParameterTypes()) b.append(descriptor(p));
        b.append(')').append(e instanceof Method ? descriptor(((Method) e).getReturnType()) : "V");
        return b.toString();
    }

    static ClassLoader loader() {
        ClassLoader l = Thread.currentThread().getContextClassLoader();
        return l != null ? l : ClassLoader.getSystemClassLoader();
    }

    static Class<?> findClass(String name) throws ClassNotFoundException {
        String n = name.replace('/', '.');
        return Class.forName(n, true, loader());
    }

    /** A method that can be called: the same method in a public type if its own class is not accessible. */
    static Method callable(Method m) {
        if (Modifier.isPublic(m.getDeclaringClass().getModifiers()) && Modifier.isPublic(m.getModifiers())) {
            if (m.getDeclaringClass().getModule().isExported(m.getDeclaringClass().getPackageName())) return m;
        }
        if (m.trySetAccessible()) return m;
        Method p = publicVersion(m.getDeclaringClass(), m.getName(), m.getParameterTypes());
        return p != null ? p : m;
    }

    private static Method publicVersion(Class<?> c, String name, Class<?>[] params) {
        if (c == null) return null;
        if (Modifier.isPublic(c.getModifiers()) && c.getModule().isExported(c.getPackageName())) {
            try {
                Method m = c.getMethod(name, params);
                if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) return m;
            } catch (NoSuchMethodException x) { /* look further */ }
        }
        for (Class<?> i : c.getInterfaces()) {
            Method m = publicVersion(i, name, params);
            if (m != null) return m;
        }
        return publicVersion(c.getSuperclass(), name, params);
    }

    static Executable lookupMethod(Class<?> c, String name, String sig, boolean wantStatic) {
        if (name.equals("<init>")) {
            if (wantStatic) return null;
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (descriptor(k).equals(sig)) { k.trySetAccessible(); return k; }
            }
            return null;
        }
        if (name.equals("<clinit>")) return null;
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers()) == wantStatic && descriptor(m).equals(sig)) return callable(m);
        }
        Deque<Class<?>> todo = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        todo.add(c);
        while (!todo.isEmpty()) {
            Class<?> k = todo.poll();
            if (!seen.add(k)) continue;
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers()) == wantStatic && descriptor(m).equals(sig)) return callable(m);
            }
            if (k.getSuperclass() != null) todo.add(k.getSuperclass());
            todo.addAll(Arrays.asList(k.getInterfaces()));
        }
        return null;
    }

    static Field lookupField(Class<?> c, String name, String sig, boolean wantStatic) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            Field f = declaredField(k, name, sig, wantStatic);
            if (f != null) return f;
            for (Class<?> i : allInterfaces(k)) {
                f = declaredField(i, name, sig, wantStatic);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static Field declaredField(Class<?> k, String name, String sig, boolean wantStatic) {
        for (Field f : k.getDeclaredFields()) {
            if (f.getName().equals(name) && Modifier.isStatic(f.getModifiers()) == wantStatic && descriptor(f.getType()).equals(sig)) {
                f.trySetAccessible();
                return f;
            }
        }
        return null;
    }

    private static Set<Class<?>> allInterfaces(Class<?> c) {
        Set<Class<?>> s = new LinkedHashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>(Arrays.asList(c.getInterfaces()));
        while (!todo.isEmpty()) {
            Class<?> i = todo.poll();
            if (s.add(i)) todo.addAll(Arrays.asList(i.getInterfaces()));
        }
        return s;
    }

    // ---- arguments and results through linear memory -----------------------------------

    /** Reads the arguments: a jvalue[] (8 bytes each), or a va_list (promoted, aligned). */
    static Object[] readArgs(Memory m, char[] kinds, int p, boolean vaList) {
        Object[] a = new Object[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            char k = kinds[i];
            if (vaList) {
                if (k == 'J' || k == 'D' || k == 'F') {
                    p = (p + 7) & ~7;
                    a[i] = k == 'J' ? (Object) m.readLong(p) : k == 'D' ? (Object) m.readDouble(p) : (Object) (float) m.readDouble(p);
                    p += 8;
                } else {
                    int v = m.readInt(p);
                    a[i] = k == 'L' ? get(v) : narrow(k, v);
                    p += 4;
                }
            } else {
                int q = p + 8 * i;
                switch (k) {
                    case 'Z': a[i] = m.read(q) != 0; break;
                    case 'B': a[i] = m.read(q); break;
                    case 'C': a[i] = (char) m.readShort(q); break;
                    case 'S': a[i] = m.readShort(q); break;
                    case 'I': a[i] = m.readInt(q); break;
                    case 'J': a[i] = m.readLong(q); break;
                    case 'F': a[i] = m.readFloat(q); break;
                    case 'D': a[i] = m.readDouble(q); break;
                    default:  a[i] = get(m.readInt(q));
                }
            }
        }
        return a;
    }

    private static Object narrow(char k, int v) {
        switch (k) {
            case 'Z': return (v & 0xff) != 0;
            case 'B': return (byte) v;
            case 'C': return (char) v;
            case 'S': return (short) v;
            default:  return v;
        }
    }

    /** Writes a value of the given kind as a jvalue; an object becomes a new local reference. */
    static void writeValue(Memory m, int p, char k, Object v) {
        switch (k) {
            case 'V': break;
            case 'Z': m.writeByte(p, (byte) ((Boolean) v ? 1 : 0)); break;
            case 'B': m.writeByte(p, (Byte) v); break;
            case 'C': m.writeShort(p, (short) (char) (Character) v); break;
            case 'S': m.writeShort(p, (Short) v); break;
            case 'I': m.writeI32(p, (Integer) v); break;
            case 'J': m.writeLong(p, (Long) v); break;
            case 'F': m.writeF32(p, (Float) v); break;
            case 'D': m.writeF64(p, (Double) v); break;
            default:  m.writeI32(p, local(v));
        }
    }

    static Object readValue(Memory m, int p, char k) {
        switch (k) {
            case 'Z': return m.read(p) != 0;
            case 'B': return m.read(p);
            case 'C': return (char) m.readShort(p);
            case 'S': return m.readShort(p);
            case 'I': return m.readInt(p);
            case 'J': return m.readLong(p);
            case 'F': return m.readFloat(p);
            case 'D': return m.readDouble(p);
            default:  return get(m.readInt(p));
        }
    }

    // ---- strings: modified UTF-8 ---------------------------------------------------------

    static int mutf8Length(String s, int start, int len) {
        int n = 0;
        for (int i = start; i < start + len; i++) {
            char c = s.charAt(i);
            n += c != 0 && c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
        }
        return n;
    }

    static byte[] mutf8(String s, int start, int len) {
        byte[] b = new byte[mutf8Length(s, start, len)];
        int j = 0;
        for (int i = start; i < start + len; i++) {
            char c = s.charAt(i);
            if (c != 0 && c < 0x80) b[j++] = (byte) c;
            else if (c < 0x800) { b[j++] = (byte) (0xc0 | c >> 6); b[j++] = (byte) (0x80 | c & 0x3f); }
            else { b[j++] = (byte) (0xe0 | c >> 12); b[j++] = (byte) (0x80 | c >> 6 & 0x3f); b[j++] = (byte) (0x80 | c & 0x3f); }
        }
        return b;
    }

    /** Decodes modified UTF-8 up to the NUL; also accepts standard UTF-8's four-byte forms. */
    static String fromMutf8(Memory m, int p) {
        StringBuilder sb = new StringBuilder();
        for (;;) {
            int c = m.read(p++) & 0xff;
            if (c == 0) break;
            if (c < 0x80) sb.append((char) c);
            else if ((c & 0xe0) == 0xc0) sb.append((char) ((c & 0x1f) << 6 | m.read(p++) & 0x3f));
            else if ((c & 0xf0) == 0xe0) {
                int c2 = m.read(p++) & 0x3f, c3 = m.read(p++) & 0x3f;
                sb.append((char) ((c & 0x0f) << 12 | c2 << 6 | c3));
            } else if ((c & 0xf8) == 0xf0) {
                int cp = (c & 0x07) << 18 | (m.read(p++) & 0x3f) << 12 | (m.read(p++) & 0x3f) << 6 | m.read(p++) & 0x3f;
                sb.appendCodePoint(cp);
            } else sb.append('�');
        }
        return sb.toString();
    }

    // ---- primitive arrays ------------------------------------------------------------------

    static int size(char k) {
        switch (k) {
            case 'Z': case 'B': return 1;
            case 'C': case 'S': return 2;
            case 'I': case 'F': return 4;
            default: return 8;
        }
    }

    static Object newArray(char k, int len) {
        switch (k) {
            case 'Z': return new boolean[len];
            case 'B': return new byte[len];
            case 'C': return new char[len];
            case 'S': return new short[len];
            case 'I': return new int[len];
            case 'J': return new long[len];
            case 'F': return new float[len];
            case 'D': return new double[len];
            default: throw new IllegalArgumentException("array kind " + k);
        }
    }

    /** Copies elements [start, start+len) of a primitive array to (set false) or from (set true) memory. */
    static void region(Memory m, Object arr, int start, int len, int buf, boolean set) {
        int n = Array.getLength(arr);
        if (start < 0 || len < 0 || start + len > n || start + len < 0)
            throw new ArrayIndexOutOfBoundsException("Array region " + start + ".." + ((long) start + len) + " out of bounds for length " + n);
        char k = kind(arr.getClass().getComponentType());
        int sz = size(k);
        if (!set) {
            byte[] b = new byte[len * sz];
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = start; i < start + len; i++) {
                switch (k) {
                    case 'Z': bb.put((byte) (((boolean[]) arr)[i] ? 1 : 0)); break;
                    case 'B': bb.put(((byte[]) arr)[i]); break;
                    case 'C': bb.putChar(((char[]) arr)[i]); break;
                    case 'S': bb.putShort(((short[]) arr)[i]); break;
                    case 'I': bb.putInt(((int[]) arr)[i]); break;
                    case 'J': bb.putLong(((long[]) arr)[i]); break;
                    case 'F': bb.putFloat(((float[]) arr)[i]); break;
                    default:  bb.putDouble(((double[]) arr)[i]);
                }
            }
            m.write(buf, b);
        } else {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(m.readBytes(buf, len * sz)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = start; i < start + len; i++) {
                switch (k) {
                    case 'Z': ((boolean[]) arr)[i] = bb.get() != 0; break;
                    case 'B': ((byte[]) arr)[i] = bb.get(); break;
                    case 'C': ((char[]) arr)[i] = bb.getChar(); break;
                    case 'S': ((short[]) arr)[i] = bb.getShort(); break;
                    case 'I': ((int[]) arr)[i] = bb.getInt(); break;
                    case 'J': ((long[]) arr)[i] = bb.getLong(); break;
                    case 'F': ((float[]) arr)[i] = bb.getFloat(); break;
                    default:  ((double[]) arr)[i] = bb.getDouble();
                }
            }
        }
    }

    // ---- calls ---------------------------------------------------------------------------

    static final int VIRTUAL = 0, NONVIRTUAL = 1, STATIC = 2, NEW = 3;

    static Object invoke(int mode, Object obj, Class<?> cls, MethodEntry me, Object[] args) throws Throwable {
        if (mode == NEW) {
            if (!(me.e instanceof Constructor)) throw new NoSuchMethodError("not a constructor: " + me.e);
            if (Modifier.isAbstract(me.e.getDeclaringClass().getModifiers())) throw new InstantiationException(me.e.getDeclaringClass().getName());
            return ((Constructor<?>) me.e).newInstance(args);
        }
        Method m = (Method) me.e;
        if (mode == STATIC) return m.invoke(null, args);
        if (obj == null) throw new NullPointerException("JNI call of " + m.getName() + " on a null object");
        if (mode == NONVIRTUAL && !Modifier.isStatic(m.getModifiers())) {
            MethodHandle h = me.special;
            if (h == null) {
                Class<?> owner = cls != null ? cls : m.getDeclaringClass();
                h = MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).unreflectSpecial(m, owner);
                me.special = h;
            }
            Object[] all = new Object[args.length + 1];
            all[0] = obj;
            System.arraycopy(args, 0, all, 1, args.length);
            return h.invokeWithArguments(all);
        }
        return m.invoke(obj, args);
    }

    // ---- the imports ---------------------------------------------------------------------

    interface Body { long apply(Instance inst, Memory m, long[] a) throws Throwable; }

    private static final List<HostFunction> fns = new ArrayList<>();

    private static void fn(String name, int nargs, Body b) {
        fn(name, nargs, ValType.I32, b);
    }

    private static void fn(String name, int nargs, ValType ret, Body b) {
        List<ValType> params = new ArrayList<>();
        for (int i = 0; i < nargs; i++) params.add(ValType.I32);
        fns.add(new HostFunction("jni", name, params, ret == null ? List.of() : List.of(ret), (inst, a) -> {
            long r;
            try {
                r = b.apply(inst, inst.memory(), a);
            } catch (Throwable x) {
                r = fail(x);
            }
            return ret == null ? null : new long[] { r };
        }));
    }

    private static int i(long[] a, int n) { return (int) a[n]; }

    /** The imports, built once (synchronized: runs are built on several threads at once). */
    static synchronized List<HostFunction> functions() {
        if (!fns.isEmpty()) return fns;

        // classes and objects
        fn("find_class", 1, (in, m, a) -> {
            String n = m.readCString(i(a, 0), java.nio.charset.StandardCharsets.UTF_8);
            try {
                return local(findClass(n));
            } catch (ClassNotFoundException x) {
                NoClassDefFoundError e = new NoClassDefFoundError(n);
                e.initCause(x);
                throw e;
            }
        });
        fn("get_object_class", 1, (in, m, a) -> local(get(i(a, 0)).getClass()));
        fn("get_superclass", 1, (in, m, a) -> {
            Class<?> c = (Class<?>) get(i(a, 0));
            return c.isInterface() ? 0 : local(c.getSuperclass());
        });
        fn("is_assignable_from", 2, (in, m, a) -> ((Class<?>) get(i(a, 1))).isAssignableFrom((Class<?>) get(i(a, 0))) ? 1 : 0);
        fn("is_instance_of", 2, (in, m, a) -> {
            Object o = get(i(a, 0));
            return o == null || ((Class<?>) get(i(a, 1))).isInstance(o) ? 1 : 0;
        });
        fn("is_same_object", 2, (in, m, a) -> get(i(a, 0)) == get(i(a, 1)) ? 1 : 0);

        // references and frames
        fn("new_ref", 2, (in, m, a) -> {
            Object o = get(i(a, 0));
            int k = i(a, 1);
            return k == LOCAL ? local(o) : o == null ? 0 : alloc(o, (byte) k, runOwner());
        });
        fn("delete_ref", 1, null, (in, m, a) -> { release(i(a, 0)); return 0; });
        fn("ref_type", 1, (in, m, a) -> refType(i(a, 0)));
        fn("push_frame", 0, (in, m, a) -> { state().frames.push(new Frame()); return 0; });
        fn("pop_frame", 1, (in, m, a) -> {
            ThreadState s = state();
            Object keep = get(i(a, 0));
            if (s.frames.size() > 1) s.frames.pop().pop();
            return local(keep);
        });
        fn("attach", 0, (in, m, a) -> {
            ThreadState s = state();
            if (s.attached++ == 0) s.frames.push(new Frame());
            return s.attached == 1 ? 0 : 1;
        });
        fn("detach", 0, (in, m, a) -> {
            ThreadState s = state();
            if (s.attached > 0 && --s.attached == 0) {
                while (s.frames.size() > 1) s.frames.pop().pop();
                s.pending = null;
            }
            return 0;
        });
        fn("is_attached", 0, (in, m, a) -> state().attached > 0 ? 1 : 0);

        // methods and fields
        fn("get_method_id", 4, (in, m, a) -> {
            Class<?> c = (Class<?>) get(i(a, 0));
            String name = m.readCString(i(a, 1), java.nio.charset.StandardCharsets.UTF_8);
            String sig = m.readCString(i(a, 2), java.nio.charset.StandardCharsets.UTF_8);
            boolean st = i(a, 3) != 0;
            String key = name + sig + st;
            synchronized (Jni.class) {
                Integer id = methodIds.computeIfAbsent(c, k -> new HashMap<>()).get(key);
                if (id != null) return id;
            }
            Executable e = lookupMethod(c, name, sig, st);
            if (e == null) throw new NoSuchMethodError((st ? "static " : "") + c.getName() + "." + name + sig);
            synchronized (Jni.class) {
                methods.add(new MethodEntry(e, st));
                int id = methods.size() - 1;
                methodIds.get(c).put(key, id);
                return id;
            }
        });
        fn("get_field_id", 4, (in, m, a) -> {
            Class<?> c = (Class<?>) get(i(a, 0));
            String name = m.readCString(i(a, 1), java.nio.charset.StandardCharsets.UTF_8);
            String sig = m.readCString(i(a, 2), java.nio.charset.StandardCharsets.UTF_8);
            boolean st = i(a, 3) != 0;
            String key = name + sig + st;
            synchronized (Jni.class) {
                Integer id = fieldIds.computeIfAbsent(c, k -> new HashMap<>()).get(key);
                if (id != null) return id;
            }
            Field f = lookupField(c, name, sig, st);
            if (f == null) throw new NoSuchFieldError((st ? "static " : "") + c.getName() + "." + name + " " + sig);
            synchronized (Jni.class) {
                fields.add(new FieldEntry(f));
                int id = fields.size() - 1;
                fieldIds.get(c).put(key, id);
                return id;
            }
        });
        // call(mode, obj, cls, mid, args, isVaList, result): result is a jvalue; returns 1, or 0 with an exception pending
        fn("call", 7, (in, m, a) -> {
            MethodEntry me = method(i(a, 3));
            Object[] args = readArgs(m, me.args, i(a, 4), i(a, 5) != 0);
            int mode = i(a, 0);
            Object r = invoke(mode, get(i(a, 1)), (Class<?>) get(i(a, 2)), me, args);
            writeValue(m, i(a, 6), mode == NEW ? 'L' : me.ret, r);
            return 1;
        });
        // field(op, obj, fid, value): op 0 get, 1 set; static fields with obj 0
        fn("field", 4, (in, m, a) -> {
            FieldEntry fe = field(i(a, 2));
            Object o = Modifier.isStatic(fe.f.getModifiers()) ? null : get(i(a, 1));
            if (o == null && !Modifier.isStatic(fe.f.getModifiers())) throw new NullPointerException("JNI field " + fe.f.getName() + " of a null object");
            if (i(a, 0) == 0) writeValue(m, i(a, 3), fe.kind, fe.f.get(o));
            else fe.f.set(o, readValue(m, i(a, 3), fe.kind));
            return 1;
        });

        // exceptions
        fn("throw", 1, (in, m, a) -> {
            Object t = get(i(a, 0));
            if (!(t instanceof Throwable)) return -1;
            state().pending = (Throwable) t;
            return 0;
        });
        fn("throw_new", 2, (in, m, a) -> {
            Class<?> c = (Class<?>) get(i(a, 0));
            int p = i(a, 1);
            String msg = p == 0 ? null : fromMutf8(m, p);
            Throwable t;
            try {
                t = (Throwable) c.getConstructor(String.class).newInstance(msg);
            } catch (NoSuchMethodException x) {
                t = (Throwable) c.getConstructor().newInstance();
            }
            state().pending = t;
            return 0;
        });
        fn("exception_occurred", 0, (in, m, a) -> local(state().pending));
        fn("exception_check", 0, (in, m, a) -> state().pending != null ? 1 : 0);
        fn("exception_clear", 0, null, (in, m, a) -> { state().pending = null; return 0; });
        fn("exception_describe", 0, null, (in, m, a) -> {
            ThreadState s = state();
            if (s.pending != null) {
                System.err.print("Exception in thread \"" + Thread.currentThread().getName() + "\" ");
                s.pending.printStackTrace();
                s.pending = null;
            }
            return 0;
        });
        fn("fatal_error", 1, null, (in, m, a) -> {
            int p = i(a, 0);
            System.err.println("FATAL ERROR in native method: " + (p == 0 ? "" : fromMutf8(m, p)));
            Thread.dumpStack();
            System.err.flush();
            Runtime.getRuntime().halt(134);
            return 0;
        });

        // strings
        fn("new_string_utf", 1, (in, m, a) -> local(fromMutf8(m, i(a, 0))));
        fn("new_string", 2, (in, m, a) -> {
            int p = i(a, 0), n = i(a, 1);
            char[] cs = new char[n];
            for (int k = 0; k < n; k++) cs[k] = (char) m.readShort(p + 2 * k);
            return local(new String(cs));
        });
        fn("string_length", 1, (in, m, a) -> ((String) get(i(a, 0))).length());
        fn("string_utf_length", 1, (in, m, a) -> {
            String s = (String) get(i(a, 0));
            return mutf8Length(s, 0, s.length());
        });
        // string_region(str, start, len, buf, utf): copies chars [start, start+len): jchar[], or modified UTF-8 (no NUL); returns bytes written
        fn("string_region", 5, (in, m, a) -> {
            String s = (String) get(i(a, 0));
            int start = i(a, 1), len = i(a, 2), buf = i(a, 3);
            if (start < 0 || len < 0 || start + len > s.length())
                throw new StringIndexOutOfBoundsException("String region " + start + ".." + (start + len) + " out of bounds for length " + s.length());
            if (i(a, 4) != 0) {
                byte[] b = mutf8(s, start, len);
                m.write(buf, b);
                return b.length;
            }
            byte[] b = new byte[2 * len];
            for (int k = 0; k < len; k++) {
                char c = s.charAt(start + k);
                b[2 * k] = (byte) c;
                b[2 * k + 1] = (byte) (c >> 8);
            }
            m.write(buf, b);
            return b.length;
        });

        // arrays
        fn("array_length", 1, (in, m, a) -> Array.getLength(get(i(a, 0))));
        fn("array_kind", 1, (in, m, a) -> (int) kind(get(i(a, 0)).getClass().getComponentType()));
        fn("new_object_array", 3, (in, m, a) -> {
            int n = i(a, 0);
            if (n < 0) throw new NegativeArraySizeException(String.valueOf(n));
            Object[] arr = (Object[]) Array.newInstance((Class<?>) get(i(a, 1)), n);
            Object init = get(i(a, 2));
            if (init != null) Arrays.fill(arr, init);
            return local(arr);
        });
        fn("get_object_element", 2, (in, m, a) -> local(((Object[]) get(i(a, 0)))[i(a, 1)]));
        fn("set_object_element", 3, null, (in, m, a) -> { ((Object[]) get(i(a, 0)))[i(a, 1)] = get(i(a, 2)); return 0; });
        fn("new_primitive_array", 2, (in, m, a) -> {
            int n = i(a, 1);
            if (n < 0) throw new NegativeArraySizeException(String.valueOf(n));
            return local(newArray((char) i(a, 0), n));
        });
        // primitive_region(arr, start, len, buf, set): returns 1, or 0 with an exception pending
        fn("primitive_region", 5, (in, m, a) -> {
            region(m, get(i(a, 0)), i(a, 1), i(a, 2), i(a, 3), i(a, 4) != 0);
            return 1;
        });

        // monitors: a Java monitor is held by a Java thread, and every wasm thread is one
        fn("monitor", 2, (in, m, a) -> {
            Object o = get(i(a, 0));
            if (o == null) throw new NullPointerException("JNI monitor of a null object");
            Monitors.op(o, i(a, 1) != 0);
            return 0;
        });
        return fns;
    }

    /** MonitorEnter / MonitorExit: a reentrant lock per object, held by the calling thread. */
    static final class Monitors {
        private static final Map<Object, java.util.concurrent.locks.ReentrantLock> locks =
            Collections.synchronizedMap(new IdentityHashMap<>());
        static void op(Object o, boolean enter) {
            java.util.concurrent.locks.ReentrantLock l;
            synchronized (locks) {
                l = locks.computeIfAbsent(o, k -> new java.util.concurrent.locks.ReentrantLock());
            }
            if (enter) l.lock();
            else {
                if (!l.isHeldByCurrentThread()) throw new IllegalMonitorStateException();
                l.unlock();
            }
        }
    }
}
