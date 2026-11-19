import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The experiment of PatchLongAddr without its extra call: for a module's Shaded copy in
 * package rexxjvm (where FlatMemory's package-private accessors can be reached), each
 * plain helper computes the effective address in long itself (base & 0xFFFFFFFFL plus
 * offset & 0xFFFFFFFFL) and calls FlatMemory's long accessor directly.
 *   java -cp ASM:. PatchLongAddrInline IN.class OUT.class
 */
public final class PatchLongAddrInline {
    // helper -> FlatMemory accessor, and the conversion around it (none, float, double)
    static final Map<String, String[]> H = Map.ofEntries(
        Map.entry("memoryReadByte", new String[] {"readL", "(J)B", ""}),
        Map.entry("memoryReadShort", new String[] {"readShortL", "(J)S", ""}),
        Map.entry("memoryReadInt", new String[] {"readIntL", "(J)I", ""}),
        Map.entry("memoryReadLong", new String[] {"readLongL", "(J)J", ""}),
        Map.entry("memoryReadFloat", new String[] {"readIntL", "(J)I", "f"}),
        Map.entry("memoryReadDouble", new String[] {"readLongL", "(J)J", "d"}),
        Map.entry("memoryWriteByte", new String[] {"writeByteL", "(JB)V", ""}),
        Map.entry("memoryWriteShort", new String[] {"writeShortL", "(JS)V", ""}),
        Map.entry("memoryWriteInt", new String[] {"writeI32L", "(JI)V", ""}),
        Map.entry("memoryWriteLong", new String[] {"writeLongL", "(JJ)V", ""}),
        Map.entry("memoryWriteFloat", new String[] {"writeI32L", "(JI)V", "f"}),
        Map.entry("memoryWriteDouble", new String[] {"writeLongL", "(JJ)V", "d"}));
    static final String FM = "rexxjvm/FlatMemory";

    static void ea(MethodVisitor mv, int baseSlot, int offSlot) {
        mv.visitVarInsn(Opcodes.ILOAD, baseSlot); mv.visitInsn(Opcodes.I2L);
        mv.visitLdcInsn(0xFFFFFFFFL); mv.visitInsn(Opcodes.LAND);
        mv.visitVarInsn(Opcodes.ILOAD, offSlot); mv.visitInsn(Opcodes.I2L);
        mv.visitLdcInsn(0xFFFFFFFFL); mv.visitInsn(Opcodes.LAND);
        mv.visitInsn(Opcodes.LADD);
    }

    public static void main(String[] args) throws Exception {
        ClassReader r = new ClassReader(Files.readAllBytes(Path.of(args[0])));
        ClassWriter w = new ClassWriter(0);
        int[] done = {0};
        r.accept(new ClassVisitor(Opcodes.ASM9, w) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                String[] h = H.get(name);
                if (h == null || (access & Opcodes.ACC_STATIC) == 0) return mv;
                done[0]++;
                Type[] a = Type.getArgumentTypes(desc);
                mv.visitCode();
                if (name.startsWith("memoryRead")) {          // (int base, int offset, Memory)
                    mv.visitVarInsn(Opcodes.ALOAD, 2);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, FM);
                    ea(mv, 0, 1);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FM, h[0], h[1], false);
                    if (h[2].equals("f")) mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false);
                    if (h[2].equals("d")) mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
                    mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
                    mv.visitMaxs(8, 3);
                } else {                                     // (int base, T value, int offset, Memory)
                    int vs = a[1].getSize(), off = 1 + vs, mem = off + 1;
                    mv.visitVarInsn(Opcodes.ALOAD, mem);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, FM);
                    ea(mv, 0, off);
                    mv.visitVarInsn(a[1].getOpcode(Opcodes.ILOAD), 1);
                    if (h[2].equals("f")) mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false);
                    if (h[2].equals("d")) mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FM, h[0], h[1], false);
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(10, mem + 1);
                }
                mv.visitEnd();
                return null;
            }
        }, 0);
        if (done[0] != H.size()) throw new IllegalStateException("rewrote " + done[0]);
        Files.write(Path.of(args[1]), w.toByteArray());
        System.out.println("rewrote " + done[0] + " helpers inline: " + args[1]);
    }
}
