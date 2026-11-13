package org.ternhdl.isa;

import java.math.BigInteger;

/** Balanced-ternary word helpers for 27-trit values held in a long. */
public final class T27 {
    private T27() { }

    public static final int W = 27, TRYTE = 9;
    public static final long M = pow3(27), H = (M - 1) / 2;
    public static final long MT = pow3(9), HT = (MT - 1) / 2;

    public static long pow3(int k) { long r = 1; for (int i = 0; i < k; i++) r *= 3; return r; }

    /** Wrap into the balanced range of n trits. */
    public static long wrap(long v, int n) {
        long m = pow3(n), h = (m - 1) / 2;
        long x = Math.floorMod(v + h, m);
        return x - h;
    }

    public static long wrap(long v) { return wrap(v, W); }

    /** Little-endian balanced digits of v (n digits, v must fit). */
    public static int[] trits(long v, int n) {
        int[] t = new int[n];
        for (int i = 0; i < n; i++) {
            int d = (int) Math.floorMod(v, 3);
            if (d == 2) d = -1;
            t[i] = d;
            v = (v - d) / 3;
        }
        if (v != 0) throw new IllegalArgumentException("value does not fit in " + n + " trits");
        return t;
    }

    public static long value(int[] t, int from, int len) {
        long v = 0;
        for (int i = from + len - 1; i >= from; i--) v = v * 3 + t[i];
        return v;
    }

    public static long value(int[] t) { return value(t, 0, t.length); }

    public static long tritwise(long a, long b, java.util.function.IntBinaryOperator f) {
        int[] x = trits(a, W), y = trits(b, W), r = new int[W];
        for (int i = 0; i < W; i++) r[i] = f.applyAsInt(x[i], y[i]);
        return value(r);
    }

    public static long shl(long a, int k) {
        int[] x = trits(a, W), r = new int[W];
        for (int i = 0; i < W; i++) r[i] = i - k >= 0 ? x[i - k] : 0;
        return value(r);
    }

    public static long shr(long a, int k) {
        int[] x = trits(a, W), r = new int[W];
        for (int i = 0; i < W; i++) r[i] = i + k < W ? x[i + k] : 0;
        return value(r);
    }

    /** {low, high}: balanced split of the 54-trit product. */
    public static long[] mul(long a, long b) {
        BigInteger p = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
        long lo = wrap(p.mod(BigInteger.valueOf(M)).longValue());
        long hi = p.subtract(BigInteger.valueOf(lo)).divide(BigInteger.valueOf(M)).longValue();
        return new long[]{lo, wrap(hi)};
    }

    public static String str(long v, int n) {
        int[] t = trits(v, n);
        StringBuilder sb = new StringBuilder();
        for (int i = n - 1; i >= 0; i--) sb.append(t[i] < 0 ? '-' : t[i] == 0 ? '0' : '+');
        return sb.toString();
    }
}
