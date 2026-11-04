package rexxjvm;

import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;

/**
 * Every outcome of call_indirect in table.wat, through a rexxj.jar built from it
 * (tests/static-table/run.sh): one line per call, the result or the exception.
 */
public class TableTest {
    public static void main(String[] a) {
        boolean resolved = TableTest.class.getClassLoader().getResource("rexxjvm/RexxModuleMachineTable.class") != null;
        System.out.println("# dispatch resolved at build time: " + resolved);
        Instance inst = Instance.builder(RexxModule.load()).withMachineFactory(RexxModule::create).build();
        ExportFunction ii = inst.export("call_ii"), v = inst.export("call_v"), lj = inst.export("call_lj");
        for (int i = -1; i <= 7; i++) {
            int k = i;
            show("call_ii(" + k + ", 10)", () -> ii.apply(k, 10));
            show("call_v(" + k + ")", () -> v.apply(k));
            show("call_lj(" + k + ", 40, 2.9)", () -> lj.apply(k, 40, Double.doubleToRawLongBits(2.9)));
        }
        show("call_ii(0x7fffffff, 1)", () -> ii.apply(0x7fffffff, 1));
        show("call_ii(-2147483648, 1)", () -> ii.apply(0x80000000L, 1));
    }

    interface Call { long[] run(); }

    static void show(String what, Call c) {
        String r;
        try {
            long[] x = c.run();
            r = "= " + (x == null || x.length == 0 ? "()" : Long.toString(x[0]));
        } catch (RuntimeException | StackOverflowError e) {
            r = "! " + e.getClass().getName() + ": " + e.getMessage();
        }
        System.out.println(what + " " + r);
    }
}
