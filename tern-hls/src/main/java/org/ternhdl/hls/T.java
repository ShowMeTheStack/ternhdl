package org.ternhdl.hls;

/** Ternary intrinsics usable in kernels; this Java implementation is the golden model (balanced digits, 27 trits). */
public final class T {
    private T() { }

    static int[] d(long v) { int[] t = new int[27]; for (int i = 0; i < 27; i++) { int r = (int) Math.floorMod(v, 3); if (r == 2) r = -1; t[i] = r; v = (v - r) / 3; } return t; }
    static long v(int[] t) { long x = 0; for (int i = 26; i >= 0; i--) x = x * 3 + t[i]; return x; }
    interface F { int f(int a, int b); }
    static long tw(long a, long b, F f) { int[] x = d(a), y = d(b), r = new int[27]; for (int i = 0; i < 27; i++) r[i] = f.f(x[i], y[i]); return v(r); }

    public static long min(long a, long b) { return tw(a, b, Math::min); }
    public static long max(long a, long b) { return tw(a, b, Math::max); }
    public static long any(long a, long b) { return tw(a, b, (p, q) -> Math.max(-1, Math.min(1, p + q))); }
    public static long cons(long a, long b) { return tw(a, b, (p, q) -> p == q ? p : 0); }
    public static long tsum(long a, long b) { return tw(a, b, (p, q) -> { int s = p + q; return s > 1 ? s - 3 : s < -1 ? s + 3 : s; }); }
    /** Multiply by 3^k (k constant). */
    public static long shl(long a, int k) { long p = 1; for (int i = 0; i < k; i++) p *= 3; return a * p; }
    /** Drop k low trits (round to nearest). */
    public static long shr(long a, int k) { int[] x = d(a), r = new int[27]; for (int i = 0; i < 27; i++) r[i] = i + k < 27 ? x[i + k] : 0; return v(r); }
}
