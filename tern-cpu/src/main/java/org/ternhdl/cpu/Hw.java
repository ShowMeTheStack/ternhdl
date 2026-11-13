package org.ternhdl.cpu;

import org.ternhdl.core.*;

/** Small hardware helpers for the cores. */
final class Hw {
    private Hw() { }

    /** n-trit constant of a balanced value. */
    static Sig k(Design d, long v, int n) {
        Logic3[] le = new Logic3[n];
        for (int i = 0; i < n; i++) { int r = (int) Math.floorMod(v, 3); if (r == 2) r = -1; le[i] = Logic3.ofBal(r); v = (v - r) / 3; }
        if (v != 0) throw new IllegalArgumentException("constant does not fit");
        StringBuilder sb = new StringBuilder();
        for (int i = n - 1; i >= 0; i--) sb.append(le[i].sym);
        return d.trits(sb.toString());
    }

    static Sig widen(Design d, Sig a, int n) { return a.width() == n ? a : k(d, 0, n - a.width()).concat(a); }

    /** Bit: field == v (balanced). */
    static Sig is(Design d, Sig field, long v) { return field.cmp(k(d, v, field.width())).isZero(); }

    /** Select vals[(index value) + offset] with a MUX3 tree over the index digits (vals.length = 3^width). */
    static Sig select(Sig idx, Sig[] vals) {
        Sig[] cur = vals;
        for (int lvl = 0; lvl < idx.width(); lvl++) {
            Sig[] nx = new Sig[cur.length / 3];
            for (int j = 0; j < nx.length; j++) nx[j] = idx.digit(lvl).mux3(cur[3 * j], cur[3 * j + 1], cur[3 * j + 2]);
            cur = nx;
        }
        return cur[0];
    }

    /** Barrel shift by the unbalanced value of amt's low three trits (0..26); left when left, else right. */
    static Sig shift(Sig x, Sig amt, boolean left) {
        Sig cur = x;
        int p = 1;
        for (int k = 0; k < 3; k++) {
            Sig one = left ? cur.shl(p) : cur.shr(p), two = left ? cur.shl(2 * p) : cur.shr(2 * p);
            cur = amt.digit(k).mux3(cur, one, two);
            p *= 3;
        }
        return cur;
    }
}
