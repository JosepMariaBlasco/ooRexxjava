import java.nio.file.*;
import org.objectweb.asm.*;

/** Experiment: lower Chicory 1.7.5's call_indirect switch sizes in Compiler.compileCallIndirect. */
public class PatchCompiler {
    public static void main(String[] a) throws Exception {
        byte[] in = Files.readAllBytes(Path.of(a[0]));
        int inline = Integer.parseInt(a[2]), chunk = Integer.parseInt(a[3]);
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        int[] n = {0};
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
                if (!name.equals("compileCallIndirect")) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public void visitIntInsn(int op, int operand) {
                        if (op == Opcodes.SIPUSH && operand == 1024) { operand = inline; n[0]++; }
                        else if (op == Opcodes.SIPUSH && operand == 4096) { operand = chunk; n[0]++; }
                        super.visitIntInsn(op, operand);
                    }
                };
            }
        }, 0);
        Files.write(Path.of(a[1]), cw.toByteArray());
        System.out.println("patched constants: " + n[0]);
    }
}
