import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * An experiment (prototypes/memory-bench, "Addresses in long"): rewrites the twelve
 * plain load and store helpers of an Endive Shaded class (the compiler's template,
 * run/endive/compiler/internal/Shaded, or a module's copy of it, ...MachineShaded) so
 * that each one only calls the method of the same name and descriptor in
 * rexxjvm.LongAddr, which computes WebAssembly's effective address in long.
 *
 *   java -cp ASM:. PatchLongAddr IN.class OUT.class
 */
public final class PatchLongAddr {
    static final Set<String> HELPERS = Set.of(
        "memoryReadByte", "memoryReadShort", "memoryReadInt", "memoryReadLong",
        "memoryReadFloat", "memoryReadDouble",
        "memoryWriteByte", "memoryWriteShort", "memoryWriteInt", "memoryWriteLong",
        "memoryWriteFloat", "memoryWriteDouble");
    static final String TARGET = "rexxjvm/LongAddr";

    public static void main(String[] args) throws Exception {
        ClassReader r = new ClassReader(Files.readAllBytes(Path.of(args[0])));
        ClassWriter w = new ClassWriter(0);
        int[] done = {0};
        r.accept(new ClassVisitor(Opcodes.ASM9, w) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!HELPERS.contains(name) || (access & Opcodes.ACC_STATIC) == 0) return mv;
                done[0]++;
                // the new body: load every argument, call LongAddr's, return its result
                mv.visitCode();
                int slot = 0;
                for (Type t : Type.getArgumentTypes(desc)) {
                    mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
                    slot += t.getSize();
                }
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET, name, desc, false);
                Type ret = Type.getReturnType(desc);
                mv.visitInsn(ret.getOpcode(Opcodes.IRETURN));
                mv.visitMaxs(Math.max(slot, ret.getSize()), slot);
                mv.visitEnd();
                return null;                     // drop the original body
            }
        }, 0);
        if (done[0] != HELPERS.size())
            throw new IllegalStateException("rewrote " + done[0] + " helpers, not " + HELPERS.size());
        Files.write(Path.of(args[1]), w.toByteArray());
        System.out.println("rewrote " + done[0] + " helpers: " + args[1]);
    }
}
