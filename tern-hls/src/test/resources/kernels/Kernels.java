import org.ternhdl.hls.*;

public class Kernels {
    public static long gcd(@Trits(9) long a, @Trits(9) long b) {
        while (a != b) {
            if (a > b) a = a - b; else b = b - a;
        }
        return a;
    }

    public static long sumsq(@Trits(4) long n) {
        long s = 0;
        for (long i = 1; i <= n; i++) s += i * i;
        return s;
    }

    public static long poly(@Trits(5) long x) {
        return 3 * x * x * x - 5 * x * x + 7 * x - 11;
    }

    public static long fir(@Trits(9) @Depth(3) long[] x) {
        long y = 0;
        for (long i = 0; i < 20; i++) y += 13 * x[(int) i] - 7 * x[(int) (i + 1)] + 40 * x[(int) (i + 2)];
        return y;
    }

    public static long dot(@Trits(9) @Depth(3) long[] x, @Trits(1) @Depth(3) long[] w, @Trits(4) long n) {
        long acc = 0;
        for (long i = 0; i < n; i++) acc += w[(int) i] * x[(int) i];
        return acc;
    }

    public static void bubble(@Trits(9) @Depth(2) long[] a) {
        for (long i = 0; i < 8; i++)
            for (long j = 0; j < 8 - i; j++) {
                long p = a[(int) j];
                long q = a[(int) (j + 1)];
                if (p > q) { a[(int) j] = q; a[(int) (j + 1)] = p; }
            }
    }

    public static long tmix(@Trits(9) long a, @Trits(9) long b) {
        return T.tsum(T.min(a, b), T.shl(T.max(a, b), 2)) + T.cons(a, b);
    }

    /** y[r] = sum_c w[r*9 + c] * x[c] for a 3 x 9 ternary-weight matrix; returns y[0] + 3*y[1] + 9*y[2] as a checksum. */
    public static long matvec(@Trits(1) @Depth(3) long[] w, @Trits(6) @Depth(2) long[] x, @Trits(12) @Depth(1) long[] y) {
        for (long r = 0; r < 3; r++) {
            long acc = 0;
            for (long c = 0; c < 9; c++) acc += w[(int) (r * 9 + c)] * x[(int) c];
            y[(int) r] = acc;
        }
        return y[0] + 3 * y[1] + 9 * y[2];
    }

    /** Sum of the balanced digits of x (digit = x - 3 * (x >> 1 trit)). */
    public static long digitsum(@Trits(12) long x) {
        long s = 0;
        while (x != 0) {
            long q = T.shr(x, 1);
            s += x - 3 * q;
            x = q;
        }
        return s;
    }
}
