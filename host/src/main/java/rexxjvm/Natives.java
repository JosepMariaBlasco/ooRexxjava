package rexxjvm;

import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.*;

/**
 * Layer 1's other direction: Java {@code native} methods implemented in the module.
 *
 * <p>A JNI library (BSF4ooRexx's) is compiled into {@code rexx.wasm}, whose exports
 * include its {@code Java_...} functions. A Java agent ({@code -javaagent:rexxj.jar},
 * {@link #premain}) rewrites, as they are loaded, the classes of the packages in
 * {@code -Drexxjvm.natives} (default {@code org/rexxla/bsf/}): each {@code native}
 * method gets a body that calls {@link #call}, and their {@code System.loadLibrary} /
 * {@code System.load} calls do nothing (the library is in the module). The jar on
 * disk is not changed.
 *
 * <p>{@link #call} runs the function on the calling thread, as JNI does: on the
 * thread's Instance if it is running wasm code already (a call from Rexx to Java that
 * calls back), else on a pthread of the module handed to it by the dispatcher (a
 * foreign entry, as in the embedded mode: {@link Rexx#foreign}). A local frame of
 * the mini-JNI ({@link Jni}) is pushed for the call, the thread counts as attached
 * during it, and an exception the native code leaves pending is thrown when it
 * returns, as with JNI.
 */
public final class Natives {
    private Natives() {}

    static final String PACKAGES = System.getProperty("rexxjvm.natives", "org/rexxla/bsf/");

    /** One rewritten native method. */
    static final class Info {
        final String owner, name, desc, shortSymbol, longSymbol;
        final boolean isStatic;
        final char[] args;
        final char ret;
        Info(String owner, String name, String desc, boolean isStatic, boolean overloaded) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.isStatic = isStatic;
            shortSymbol = "Java_" + mangle(owner) + "_" + mangle(name);
            String params = desc.substring(1, desc.indexOf(')'));
            longSymbol = shortSymbol + "__" + mangle(params);
            Type[] at = Type.getArgumentTypes(desc);
            args = new char[at.length];
            for (int i = 0; i < at.length; i++) args[i] = kind(at[i]);
            ret = kind(Type.getReturnType(desc));
            preferLong = overloaded;
        }
        final boolean preferLong;
    }

    private static final List<Info> infos = Collections.synchronizedList(new ArrayList<>());
    /** The id of each rewritten method, by owner, name and descriptor (guarded by infos). */
    private static final Map<String, Integer> ids = new HashMap<>();

    static char kind(Type t) {
        switch (t.getSort()) {
            case Type.VOID: return 'V';
            case Type.BOOLEAN: return 'Z';
            case Type.BYTE: return 'B';
            case Type.CHAR: return 'C';
            case Type.SHORT: return 'S';
            case Type.INT: return 'I';
            case Type.LONG: return 'J';
            case Type.FLOAT: return 'F';
            case Type.DOUBLE: return 'D';
            default: return 'L';
        }
    }

    /** JNI's name mangling. */
    static String mangle(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '.') b.append('_');
            else if (c == '_') b.append("_1");
            else if (c == ';') b.append("_2");
            else if (c == '[') b.append("_3");
            else if (c < 128 && Character.isLetterOrDigit(c)) b.append(c);
            else b.append(String.format("_0%04x", (int) c));
        }
        return b.toString();
    }

    // ---- the agent ---------------------------------------------------------------------

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new Transformer());
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    static boolean selected(String className) {
        if (className == null) return false;
        for (String p : PACKAGES.split("[,:]")) if (!p.isEmpty() && className.startsWith(p)) return true;
        return false;
    }

    static final class Transformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
            if (!selected(name)) return null;
            try {
                return rewrite(bytes);
            } catch (Throwable x) {
                System.err.println("rexxjvm: cannot rewrite the native methods of " + name + ": " + x);
                return null;
            }
        }
    }

    /** The class with its native methods given bodies; null if it has none. */
    static byte[] rewrite(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        Map<String, Integer> nativeNames = new HashMap<>();
        boolean[] any = new boolean[1];
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if ((access & Opcodes.ACC_NATIVE) != 0) {
                    nativeNames.merge(name, 1, Integer::sum);
                    any[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE);
        if (!any[0]) return null;
        String owner = cr.getClassName();
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if ((access & Opcodes.ACC_NATIVE) == 0) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, ex)) {
                        @Override
                        public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                            if (op == Opcodes.INVOKESTATIC && o.equals("java/lang/System")
                                && (n.equals("loadLibrary") || n.equals("load")) && d.equals("(Ljava/lang/String;)V")) {
                                super.visitMethodInsn(op, "rexxjvm/Natives", "loadLibrary", d, false);
                            } else super.visitMethodInsn(op, o, n, d, itf);
                        }
                    };
                }
                boolean st = (access & Opcodes.ACC_STATIC) != 0;
                // one id per method, whichever class loader loads its class (a server's
                // runs each load BSF4ooRexx again): the list does not grow with the runs
                boolean overloaded = nativeNames.get(name) > 1;
                int id;
                synchronized (infos) {
                    Integer known = ids.get(owner + "." + name + desc);
                    if (known != null) id = known;
                    else {
                        infos.add(new Info(owner, name, desc, st, overloaded));
                        id = infos.size() - 1;
                        ids.put(owner + "." + name + desc, id);
                    }
                }
                MethodVisitor mv = super.visitMethod(access & ~Opcodes.ACC_NATIVE, name, desc, sig, ex);
                mv.visitCode();
                mv.visitLdcInsn(id);
                if (st) mv.visitLdcInsn(Type.getObjectType(owner));
                else mv.visitVarInsn(Opcodes.ALOAD, 0);
                Type[] at = Type.getArgumentTypes(desc);
                mv.visitLdcInsn(at.length);
                mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                int slot = st ? 0 : 1;
                for (int i = 0; i < at.length; i++) {
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitLdcInsn(i);
                    mv.visitVarInsn(at[i].getOpcode(Opcodes.ILOAD), slot);
                    box(mv, at[i]);
                    mv.visitInsn(Opcodes.AASTORE);
                    slot += at[i].getSize();
                }
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "rexxjvm/Natives", "call",
                    "(ILjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                Type rt = Type.getReturnType(desc);
                if (rt.getSort() == Type.VOID) {
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.RETURN);
                } else {
                    unbox(mv, rt);
                    mv.visitInsn(rt.getOpcode(Opcodes.IRETURN));
                }
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                return null;
            }
        }, 0);
        return cw.toByteArray();
    }

    private static void box(MethodVisitor mv, Type t) {
        String w = wrapper(t);
        if (w != null) mv.visitMethodInsn(Opcodes.INVOKESTATIC, w, "valueOf", "(" + t.getDescriptor() + ")L" + w + ";", false);
    }

    private static void unbox(MethodVisitor mv, Type t) {
        String w = wrapper(t);
        if (w == null) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, t.getInternalName());
            return;
        }
        mv.visitTypeInsn(Opcodes.CHECKCAST, w);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, w, t.getClassName() + "Value", "()" + t.getDescriptor(), false);
    }

    private static String wrapper(Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN: return "java/lang/Boolean";
            case Type.BYTE: return "java/lang/Byte";
            case Type.CHAR: return "java/lang/Character";
            case Type.SHORT: return "java/lang/Short";
            case Type.INT: return "java/lang/Integer";
            case Type.LONG: return "java/lang/Long";
            case Type.FLOAT: return "java/lang/Float";
            case Type.DOUBLE: return "java/lang/Double";
            default: return null;
        }
    }

    // ---- at run time ----------------------------------------------------------------------

    /** What the rewritten classes call instead of System.loadLibrary: the library is in the module. */
    public static void loadLibrary(String name) {}

    /**
     * The exports this thread's Instance has looked up, by native method: an Instance is
     * used by one thread, and lives while the thread runs wasm code. (A map keyed by
     * Instance kept every Instance alive: its exports refer to it.)
     */
    private static final class ExportCache {
        final Instance inst;
        final Map<Integer, ExportFunction> byId = new HashMap<>();
        ExportCache(Instance inst) { this.inst = inst; }
    }

    private static final ThreadLocal<ExportCache> exports = new ThreadLocal<>();

    /** The body of every rewritten native method. */
    public static Object call(int id, Object self, Object[] args) {
        Info info = infos.get(id);
        Instance inst = Run.INSTANCE.get();
        if (inst == null) {
            // a Java thread that is not running wasm code: a pthread of the module is
            // handed to it (a foreign entry), and the call runs there, still on this
            // thread. Which module: the run whose class loader loaded the class (a
            // server's run), else the run that serves such threads (the first one); a
            // Java host that never started the module (JSR-223): it is started now.
            Class<?> c = self instanceof Class ? (Class<?>) self : self.getClass();
            Run run = Run.forLoader(c.getClassLoader());
            if (run == null) run = Rexx.primaryOrStart();
            Object[] out = run.entries.foreign(i -> {
                try {
                    return new Object[] { call(id, self, args), null };
                } catch (Throwable x) {
                    return new Object[] { null, x };
                }
            });
            if (out[1] != null) throwUnchecked((Throwable) out[1]);
            return out[0];
        }
        ExportCache cache = exports.get();
        if (cache == null || cache.inst != inst) exports.set(cache = new ExportCache(inst));
        ExportFunction f = cache.byId.computeIfAbsent(id, k -> export(inst, info));
        Run run = Run.current();
        int env = run.jniEnv;
        if (env == 0) run.jniEnv = env = (int) inst.export("orx_jni_env").apply()[0];
        int depth = Jni.enterNative();
        Object result;
        try {
            long[] w = new long[2 + args.length];
            w[0] = env;
            w[1] = Jni.local(self);
            for (int i = 0; i < args.length; i++) w[2 + i] = toWasm(info.args[i], args[i]);
            long[] r = f.apply(w);
            result = info.ret == 'V' ? null : fromWasm(info.ret, r[0]);
        } finally {
            Throwable pending = Jni.exitNative(depth);
            if (pending != null) throwUnchecked(pending);
        }
        return result;
    }

    private static ExportFunction export(Instance inst, Info info) {
        String first = info.preferLong ? info.longSymbol : info.shortSymbol;
        String second = info.preferLong ? info.shortSymbol : info.longSymbol;
        try {
            return inst.export(first);
        } catch (RuntimeException x) {
            try {
                return inst.export(second);
            } catch (RuntimeException y) {
                throw new UnsatisfiedLinkError("'" + info.owner.replace('/', '.') + "." + info.name + info.desc
                    + "': the module exports neither " + first + " nor " + second);
            }
        }
    }

    private static long toWasm(char k, Object v) {
        switch (k) {
            case 'Z': return (Boolean) v ? 1 : 0;
            case 'B': return (Byte) v;
            case 'C': return (Character) v;
            case 'S': return (Short) v;
            case 'I': return (Integer) v;
            case 'J': return (Long) v;
            case 'F': return Float.floatToRawIntBits((Float) v) & 0xffffffffL;
            case 'D': return Double.doubleToRawLongBits((Double) v);
            default:  return Jni.local(v);
        }
    }

    private static Object fromWasm(char k, long r) {
        switch (k) {
            case 'Z': return (r & 0xff) != 0;
            case 'B': return (byte) r;
            case 'C': return (char) r;
            case 'S': return (short) r;
            case 'I': return (int) r;
            case 'J': return r;
            case 'F': return Float.intBitsToFloat((int) r);
            case 'D': return Double.longBitsToDouble(r);
            default:  return Jni.get((int) r);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable t) throws T {
        throw (T) t;
    }
}
