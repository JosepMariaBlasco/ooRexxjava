// The call_indirect dispatch of a module whose table never changes, resolved at build
// time (host/static-table/README.md). Run by host/pom.xml's embedded profile, after
// Endive's build-time compiler, in Java's source-file mode:
//
//   java -cp CLASSPATH StaticTable.java CLASSES WASM PREFIX
//
// CLASSES: the folder with Endive's generated classes (target/classes); WASM: the module
// they were compiled from; PREFIX: the machine's class name, with slashes
// (rexxjvm/RexxModuleMachine). If the table may change, it says why and leaves the
// classes as they are.

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.ActiveElement;
import run.endive.wasm.types.Element;
import run.endive.wasm.types.ExternalType;
import run.endive.wasm.types.FunctionBody;
import run.endive.wasm.types.Instruction;
import run.endive.wasm.types.OpCode;

public class StaticTable {
    /** Most cases in one dispatch method: 12 bytes each, under HotSpot's 8,000-byte limit. */
    static final int MAX_CASES = 500;
    /** The table's indices in blocks of this many when a signature has more cases. */
    static final int BLOCK_SHIFT = 9;

    record Target(String owner, String name, String desc) {}

    public static void main(String[] args) throws IOException {
        Path classes = Path.of(args[0]);
        WasmModule module = Parser.parse(Path.of(args[1]));
        String machine = args[2];
        String why = whyNotStatic(module);
        if (why != null) {
            System.out.println("static-table: not applied, " + why);
            return;
        }
        int[] table = contents(module);       // table index -> function index, -1 if none

        Path machineFile = classes.resolve(machine + ".class");
        ClassNode cn = new ClassNode();
        new ClassReader(Files.readAllBytes(machineFile)).accept(cn, 0);
        String dispatcher = machine + "Table";
        ClassWriter dw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        dw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, dispatcher,
                 null, "java/lang/Object", null);

        List<MethodNode> added = new ArrayList<>();
        int done = 0, cases = 0;
        for (MethodNode m : cn.methods) {
            if (!m.name.matches("call_indirect_\\d+") || (m.access & Opcodes.ACC_STATIC) == 0) continue;
            // Endive's own dispatch for this signature: function index -> the method it calls
            Map<Integer, Target> byFunction = new HashMap<>();
            harvest(m, byFunction);
            for (String c : indirectClasses(classes, machine, m.name.substring("call_indirect_".length()))) {
                ClassNode ic = new ClassNode();
                new ClassReader(Files.readAllBytes(classes.resolve(c))).accept(ic, 0);
                for (MethodNode im : ic.methods) harvest(im, byFunction);
            }
            TreeMap<Integer, Target> byIndex = new TreeMap<>();
            for (int i = 0; i < table.length; i++) {
                Target t = table[i] < 0 ? null : byFunction.get(table[i]);
                if (t != null) byIndex.put(i, t);
            }
            if (byIndex.isEmpty()) continue;
            String endive = m.name + "$endive";
            dispatch(dw, machine, dispatcher, m.name, endive, m.desc, byIndex);
            // the machine's call_indirect_N becomes a call to the dispatcher's (small: HotSpot
            // inlines it); Endive's body stays as call_indirect_N$endive, for everything the
            // dispatcher does not know (another table, an empty entry, a wrong signature)
            MethodNode moved = new MethodNode(m.access, endive, m.desc, m.signature,
                                              m.exceptions.toArray(new String[0]));
            m.accept(moved);
            moved.name = endive;
            added.add(moved);
            m.instructions = forward(m.desc, dispatcher, m.name);
            m.tryCatchBlocks = new ArrayList<>();
            m.localVariables = null;
            m.visibleParameterAnnotations = null;
            m.maxStack = Type.getArgumentsAndReturnSizes(m.desc) >> 2;
            m.maxLocals = m.maxStack;
            done++;
            cases += byIndex.size();
        }
        cn.methods.addAll(added);
        dw.visitEnd();
        ClassWriter mw = new ClassWriter(0);
        cn.accept(mw);
        Files.write(machineFile, mw.toByteArray());
        Files.write(classes.resolve(dispatcher + ".class"), dw.toByteArray());
        System.out.println("static-table: " + done + " call_indirect methods, " + cases
                           + " table entries, in " + dispatcher);
    }

    /** Why the module's table may change at run time, or null if it cannot. */
    static String whyNotStatic(WasmModule module) {
        if (module.tableSection().tableCount() != 1) return "the module defines " + module.tableSection().tableCount() + " tables";
        if (module.importSection().count(ExternalType.TABLE) != 0) return "it imports a table";
        for (int i = 0; i < module.exportSection().exportCount(); i++)
            if (module.exportSection().getExport(i).exportType() == ExternalType.TABLE) return "it exports its table";
        for (Element e : module.elementSection().elements()) {
            if (!(e instanceof ActiveElement a)) continue;   // passive ones need table.init
            List<Instruction> off = a.offset();
            if (a.tableIndex() != 0 || off.isEmpty() || off.get(0).opcode() != OpCode.I32_CONST
                || off.size() > 2 || (off.size() == 2 && off.get(1).opcode() != OpCode.END))
                return "an element segment's offset is not a constant: " + off;
        }
        for (FunctionBody body : module.codeSection().functionBodies())
            for (Instruction ins : body.instructions())
                switch (ins.opcode()) {
                    case TABLE_SET, TABLE_GROW, TABLE_FILL, TABLE_COPY, TABLE_INIT:
                        return "it has a " + ins.opcode() + " instruction";
                    default:
                }
        return null;
    }

    /** The table after instantiation: function index per entry, -1 where there is none. */
    static int[] contents(WasmModule module) {
        int[] t = new int[(int) module.tableSection().getTable(0).limits().min()];
        java.util.Arrays.fill(t, -1);
        for (Element e : module.elementSection().elements()) {
            if (!(e instanceof ActiveElement a)) continue;
            int off = (int) a.offset().get(0).operand(0);
            List<List<Instruction>> init = a.initializers();
            for (int i = 0; i < init.size(); i++) {
                List<Instruction> x = init.get(i);
                int idx = off + i;
                if (idx < 0 || idx >= t.length) throw new IllegalStateException("element past the table");
                t[idx] = x.size() >= 1 && x.get(0).opcode() == OpCode.REF_FUNC ? (int) x.get(0).operand(0) : -1;
            }
        }
        return t;
    }

    /** The classes of Endive's dispatch for signature n, if it split it (MachineIndirect_n_k). */
    static List<String> indirectClasses(Path classes, String machine, String n) throws IOException {
        Path dir = classes.resolve(machine).getParent();
        String base = Path.of(machine).getFileName() + "Indirect_" + n + "_";
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(f -> f.startsWith(base) && f.endsWith(".class"))
                    .map(f -> classes.relativize(dir.resolve(f)).toString()).toList();
        }
    }

    /** From each switch of m: the key (a function index) -> the module's function it calls. */
    static void harvest(MethodNode m, Map<Integer, Target> out) {
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof LookupSwitchInsnNode ls) {
                for (int k = 0; k < ls.keys.size(); k++) record(ls.keys.get(k), ls.labels.get(k), out);
            } else if (i instanceof TableSwitchInsnNode ts) {
                for (int k = 0; k < ts.labels.size(); k++) record(ts.min + k, ts.labels.get(k), out);
            }
        }
    }

    static void record(int key, LabelNode label, Map<Integer, Target> out) {
        for (AbstractInsnNode i = label; i != null; i = i.getNext()) {
            if (i.getOpcode() < 0) continue;                  // labels, frames, line numbers
            if (i instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                && mi.owner.contains("FuncGroup") && mi.name.startsWith("func_")) {
                Target prev = out.put(key, new Target(mi.owner, mi.name, mi.desc));
                if (prev != null && !prev.equals(out.get(key)))
                    throw new IllegalStateException("function " + key + " dispatched to two methods");
            }
            return;                                           // the first instruction only
        }
    }

    /** The body of the machine's call_indirect_N: all its arguments to the dispatcher's. */
    static InsnList forward(String desc, String dispatcher, String name) {
        InsnList l = new InsnList();
        int slot = 0;
        for (Type t : Type.getArgumentTypes(desc)) {
            l.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
            slot += t.getSize();
        }
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, dispatcher, name, desc, false));
        l.add(new InsnNode(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN)));
        return l;
    }

    /*
     * The dispatcher's call_indirect_N, same descriptor as Endive's: (wasm arguments...,
     * int tableIndex, int tableNumber, Memory, Instance). For table 0, a switch on the
     * table index straight to the function; anything else, Endive's call_indirect_N$endive.
     * Endive's check for an interruption at every indirect call is kept.
     */
    static void dispatch(ClassWriter cw, String machine, String dispatcher, String name, String endive,
                         String desc, TreeMap<Integer, Target> byIndex) {
        Type[] args = Type.getArgumentTypes(desc);
        int n = args.length;                                  // ..., index, number, Memory, Instance
        int[] slot = new int[n];
        for (int i = 0, s = 0; i < n; i++) { slot[i] = s; s += args[i].getSize(); }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        Label toEndive = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, slot[n - 3]);          // the table's number
        mv.visitJumpInsn(Opcodes.IFNE, toEndive);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, machine + "Shaded", "checkInterruption", "()V", false);
        if (byIndex.size() <= MAX_CASES) {
            cases(mv, args, slot, byIndex, machine, endive, desc);
        } else {
            // blocks of the table's indices, each in a method of its own
            TreeMap<Integer, TreeMap<Integer, Target>> blocks = new TreeMap<>();
            byIndex.forEach((k, t) -> blocks.computeIfAbsent(k >> BLOCK_SHIFT, b -> new TreeMap<>()).put(k, t));
            int first = blocks.firstKey(), last = blocks.lastKey();
            loadAll(mv, args, slot);
            mv.visitVarInsn(Opcodes.ILOAD, slot[n - 4]);
            mv.visitIntInsn(Opcodes.BIPUSH, BLOCK_SHIFT);
            mv.visitInsn(Opcodes.IUSHR);
            Label dflt = new Label();
            Label[] ls = new Label[last - first + 1];
            for (int b = first; b <= last; b++) ls[b - first] = blocks.containsKey(b) ? new Label() : dflt;
            mv.visitTableSwitchInsn(first, last, dflt, ls);
            for (int b = first; b <= last; b++) {
                if (!blocks.containsKey(b)) continue;
                mv.visitLabel(ls[b - first]);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, dispatcher, name + "$" + b, desc, false);
                mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
            }
            mv.visitLabel(dflt);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, machine, endive, desc, false);
            mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
            for (var e : blocks.entrySet()) {
                MethodVisitor bv = cw.visitMethod(Opcodes.ACC_STATIC, name + "$" + e.getKey(), desc, null, null);
                bv.visitCode();
                cases(bv, args, slot, e.getValue(), machine, endive, desc);
                bv.visitMaxs(0, 0);
                bv.visitEnd();
            }
        }
        mv.visitLabel(toEndive);
        loadAll(mv, args, slot);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, machine, endive, desc, false);
        mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A lookupswitch on the table index; every case calls its function, the rest Endive's. */
    static void cases(MethodVisitor mv, Type[] args, int[] slot, TreeMap<Integer, Target> byIndex,
                      String machine, String endive, String desc) {
        int n = args.length;
        // the function's arguments first (wasm arguments, Memory, Instance), as Endive does
        for (int i = 0; i < n - 4; i++) mv.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slot[i]);
        mv.visitVarInsn(Opcodes.ALOAD, slot[n - 2]);
        mv.visitVarInsn(Opcodes.ALOAD, slot[n - 1]);
        mv.visitVarInsn(Opcodes.ILOAD, slot[n - 4]);
        int[] keys = byIndex.keySet().stream().mapToInt(Integer::intValue).toArray();
        Label[] ls = new Label[keys.length];
        for (int i = 0; i < ls.length; i++) ls[i] = new Label();
        Label dflt = new Label();
        mv.visitLookupSwitchInsn(dflt, keys, ls);
        int ret = Type.getReturnType(desc).getOpcode(Opcodes.IRETURN);
        int i = 0;
        for (Target t : byIndex.values()) {
            mv.visitLabel(ls[i++]);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, t.owner(), t.name(), t.desc(), false);
            mv.visitInsn(ret);
        }
        mv.visitLabel(dflt);
        // drop the function's arguments, then Endive's with everything
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.POP);
        for (int k = n - 5; k >= 0; k--) mv.visitInsn(args[k].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        loadAll(mv, args, slot);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, machine, endive, desc, false);
        mv.visitInsn(ret);
    }

    static void loadAll(MethodVisitor mv, Type[] args, int[] slot) {
        for (int i = 0; i < args.length; i++) mv.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slot[i]);
    }
}
