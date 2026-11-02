package jnitest;

import java.util.Iterator;
import java.util.List;

/** What tests/jni/jnitest.c calls through the mini-JNI. */
public class JniTarget extends JniBase {
    public static int sCount = 7;
    public static String sName = "static";

    public boolean z = true;
    public byte b = -3;
    public char c = 'é';
    public short s = -300;
    public int i = 42;
    public long j = 1L << 40;
    public float f = 1.5f;
    public double d = 2.5;
    public String str = "field";
    private int secret = 99;

    public JniTarget() {}

    public JniTarget(int i, String str) {
        this.i = i;
        this.str = str;
    }

    public int add(int x, int y) { return x + y; }

    /** Every kind of argument, so that a wrong promotion or alignment shows. */
    public long mix(boolean z, byte b, char c, short s, int i, long j, float f, double d, String o) {
        return (z ? 1 : 0) + b + c + s + i + j + (long) (f * 10) + (long) (d * 100) + o.length();
    }

    public double half(float f, double d) { return f / 2 + d; }

    public static String hello(String who) { return "hello, " + who; }

    public static void boom(String msg) { throw new IllegalStateException(msg); }

    @Override public String name() { return "target"; }

    public static int[] squares(int n) {
        int[] a = new int[n];
        for (int k = 0; k < n; k++) a[k] = k * k;
        return a;
    }

    public static long sum(int[] a) {
        long t = 0;
        for (int v : a) t += v;
        return t;
    }

    public static String join(String[] a) { return String.join(",", a); }

    public static String kinds(boolean[] z, byte[] b, char[] c, short[] s, long[] j, float[] f, double[] d) {
        StringBuilder sb = new StringBuilder();
        sb.append(z[0]).append(z[1]).append(' ').append(b[0] + b[1]).append(' ').append(c[0]).append(c[1]).append(' ')
          .append(s[0] + s[1]).append(' ').append(j[0] + j[1]).append(' ').append(f[0] + f[1]).append(' ').append(d[0] + d[1]);
        return sb.toString();
    }

    private String hidden() { return "hidden"; }

    public static Iterator<String> iter() { return List.of("a", "b").iterator(); }

    public static String codes(String x) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < x.length(); k++) sb.append(Integer.toHexString(x.charAt(k))).append(' ');
        return sb.toString().trim();
    }
}

class JniBase {
    public String name() { return "base"; }
}
