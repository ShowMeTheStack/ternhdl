package org.ternhdl.stdlib;

import org.ternhdl.core.*;

/** Arithmetic building blocks (balanced ternary). */
public final class Arith {
    private Arith() { }

    public static Sig zeros(Design d, int n) { return d.trits("0".repeat(n)); }
    public static Sig ones(Design d, int n, char c) { return d.trits(String.valueOf(c).repeat(n)); }

    /** Sign-free widening of a balanced number by k digits (balanced numbers extend with O). */
    public static Sig widen(Design d, Sig a, int k) { return k == 0 ? a : zeros(d, k).concat(a); }

    /** a + b + cin as n+1 digits (top digit = carry out). */
    public static Sig addWide(Design d, Sig a, Sig b, Sig cin) { return widen(d, a, 1).add(widen(d, b, 1), cin); }

    /** a * digit t (t one trit): -a, 0 or a. */
    public static Sig mulDigit(Design d, Sig a, Sig t) { return t.mux3(a.neg(), zeros(d, a.width()), a); }

    /** Carry-select adder: blocks of k digits, each computed for carry-in N, O, P and selected by the incoming carry. */
    public static Sig carrySelect(Design d, Sig a, Sig b, Sig cin, int k) {
        int n = a.width();
        Sig carry = cin;
        Sig result = null;
        for (int lo = 0; lo < n; lo += k) {
            int hi = Math.min(n, lo + k) - 1;
            Sig as = a.slice(hi, lo), bs = b.slice(hi, lo);
            Sig sN = addWide(d, as, bs, d.trits("-")), sO = addWide(d, as, bs, d.trits("0")), sP = addWide(d, as, bs, d.trits("+"));
            Sig sel = carry.mux3(sN, sO, sP);
            Sig sum = sel.slice(hi - lo, 0);
            carry = sel.digit(hi - lo + 1);
            result = result == null ? sum : sum.concat(result);
        }
        return carry.concat(result);   // n+1 digits
    }

    /**
     * Parallel-prefix (Sklansky) adder over balanced carry-transfer functions. Each digit's transfer function is a
     * 3-trit table T[c] = carry(a_i + b_i + c) for c = N, O, P; composition (hi o lo)[c] = MUX3(lo[c], hi[N], hi[O], hi[P]).
     */
    public static Sig prefixAdd(Design d, Sig a, Sig b, Sig cin) {
        int n = a.width();
        Sig[][] t = new Sig[n][3];
        String[] cs = {"-", "0", "+"};
        for (int i = 0; i < n; i++) for (int c = 0; c < 3; c++)
            t[i][c] = addWide(d, a.digit(i), b.digit(i), d.trits(cs[c])).digit(1);
        Sig[][] pre = new Sig[n][];
        for (int i = 0; i < n; i++) pre[i] = t[i];
        for (int span = 1; span < n; span *= 2) {
            Sig[][] nx = new Sig[n][];
            for (int i = 0; i < n; i++) {
                if ((i / span) % 2 == 1) {
                    int j = (i / span) * span - 1;    // last index of the lower block
                    Sig[] hi = pre[i], lo = pre[j];
                    nx[i] = new Sig[3];
                    for (int c = 0; c < 3; c++) nx[i][c] = lo[c].mux3(hi[0], hi[1], hi[2]);
                } else nx[i] = pre[i];
            }
            pre = nx;
        }
        Sig result = null;
        Sig carryIn = cin;
        for (int i = 0; i < n; i++) {
            Sig ci = i == 0 ? cin : cin.mux3(pre[i - 1][0], pre[i - 1][1], pre[i - 1][2]);
            Sig s = a.digit(i).sum(b.digit(i)).sum(ci);
            result = result == null ? s : s.concat(result);
        }
        Sig cout = cin.mux3(pre[n - 1][0], pre[n - 1][1], pre[n - 1][2]);
        return cout.concat(result);
    }

    /** n x n balanced multiplier, 2n-digit exact product. */
    public static Sig mul(Design d, Sig a, Sig b) {
        int n = a.width();
        Sig acc = zeros(d, 2 * n);
        for (int i = 0; i < n; i++) {
            Sig pp = widen(d, mulDigit(d, a, b.digit(i)), n).shl(i);
            acc = acc.add(pp);
        }
        return acc;
    }

    /** Saturating add: n digits, clamps to +-(3^n - 1)/2 on overflow. */
    public static Sig satAdd(Design d, Sig a, Sig b) {
        int n = a.width();
        Sig w = widen(d, a, 1).add(widen(d, b, 1));
        Sig top = w.digit(n), low = w.slice(n - 1, 0);
        return top.mux3(ones(d, n, '-'), low, ones(d, n, '+'));
    }
}
