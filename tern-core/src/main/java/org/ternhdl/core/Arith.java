package org.ternhdl.core;

import java.math.BigInteger;

/** Reference arithmetic on little-endian digit arrays (spec section 5). The golden model for every simulator. */
public final class Arith {
    private Arith() { }

    /** Balanced add, wrapping at the operand width. An unknown digit or carry makes that digit and every higher one X. */
    public static Logic3[] add(Logic3[] a, Logic3[] b, Logic3 cin) {
        int n = a.length;
        if (b.length != n) throw new IllegalArgumentException("width mismatch");
        Logic3[] r = new Logic3[n];
        boolean unknown = !cin.isLevel();
        int carry = unknown ? 0 : cin.bal();
        for (int i = 0; i < n; i++) {
            if (unknown || !a[i].isLevel() || !b[i].isLevel()) { unknown = true; r[i] = Logic3.X; continue; }
            int s = a[i].bal() + b[i].bal() + carry;
            int c = s >= 2 ? 1 : s <= -2 ? -1 : 0;
            r[i] = Logic3.ofBal(s - 3 * c);
            carry = c;
        }
        return r;
    }

    /** sign(a - b) scanning from the MSB; an unknown digit pair before a known difference gives X. */
    public static Logic3 cmp(Logic3[] a, Logic3[] b) {
        for (int i = a.length - 1; i >= 0; i--) {
            if (!a[i].isLevel() || !b[i].isLevel()) return Logic3.X;
            int d = Integer.signum(a[i].bal() - b[i].bal());
            if (d != 0) return Logic3.ofBal(d);
        }
        return Logic3.O;
    }

    public static Logic3[] shl(Logic3[] a, int k) {
        int n = a.length;
        Logic3[] r = new Logic3[n];
        for (int i = 0; i < n; i++) r[i] = i - k >= 0 ? a[i - k] : Logic3.O;
        return r;
    }

    public static Logic3[] shr(Logic3[] a, int k) {
        int n = a.length;
        Logic3[] r = new Logic3[n];
        for (int i = 0; i < n; i++) r[i] = i + k < n ? a[i + k] : Logic3.O;
        return r;
    }

    private static final BigInteger THREE = BigInteger.valueOf(3);

    /** Unbalanced (index-form) value: digits N, O, P read as 0, 1, 2. Equals the balanced value plus (3^n - 1)/2. */
    public static BigInteger toUnbalanced(Logic3[] v) {
        BigInteger r = BigInteger.ZERO;
        for (int i = v.length - 1; i >= 0; i--) r = r.multiply(THREE).add(BigInteger.valueOf(v[i].ordinal()));
        return r;
    }

    /** Balanced value of a vector of levels. */
    public static BigInteger toBig(Logic3[] v) {
        BigInteger r = BigInteger.ZERO;
        for (int i = v.length - 1; i >= 0; i--) r = r.multiply(THREE).add(BigInteger.valueOf(v[i].bal()));
        return r;
    }

    /** n-digit balanced vector of x, wrapped into the balanced range of n digits. */
    public static Logic3[] fromBig(BigInteger x, int n) {
        BigInteger mod = THREE.pow(n);
        BigInteger h = mod.subtract(BigInteger.ONE).shiftRight(1);
        x = x.add(h).mod(mod).subtract(h);
        Logic3[] r = new Logic3[n];
        for (int i = 0; i < n; i++) {
            int d = x.mod(THREE).intValue();
            if (d == 2) d = -1;
            r[i] = Logic3.ofBal(d);
            x = x.subtract(BigInteger.valueOf(d)).divide(THREE);
        }
        return r;
    }
}
