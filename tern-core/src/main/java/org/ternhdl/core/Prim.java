package org.ternhdl.core;

/** Primitive digit-wise operations (spec section 4). Level semantics plus the exact X-extension. */
public enum Prim {
    BUF(1, a -> a[0]),
    STI(1, a -> -a[0]),
    PTI(1, a -> a[0] == 1 ? -1 : 1),
    NTI(1, a -> a[0] == -1 ? 1 : -1),
    ICI(1, a -> a[0] == 1 ? -1 : a[0] + 1),
    DCI(1, a -> a[0] == -1 ? 1 : a[0] - 1),
    ISZERO(1, a -> a[0] == 0 ? 1 : -1),
    MIN(2, a -> Math.min(a[0], a[1])),
    MAX(2, a -> Math.max(a[0], a[1])),
    ANY(2, a -> Math.max(-1, Math.min(1, a[0] + a[1]))),
    CONS(2, a -> a[0] == a[1] ? a[0] : 0),
    SUM(2, a -> { int s = a[0] + a[1]; return s > 1 ? s - 3 : s < -1 ? s + 3 : s; }),
    /** Arguments (select, a, b, c): select N picks a, O picks b, P picks c. */
    MUX3(4, a -> a[0] == -1 ? a[1] : a[0] == 0 ? a[2] : a[3]),
    TIE_N(0, a -> -1), TIE_O(0, a -> 0), TIE_P(0, a -> 1);

    public interface Fn { int apply(int[] a); }

    public final int arity;
    private final Fn fn;
    Prim(int arity, Fn fn) { this.arity = arity; this.fn = fn; }

    /** Level function on balanced values. */
    public int level(int... a) { return fn.apply(a); }

    /**
     * Exact X-extension over the Bit domain: an unknown Bit is 0 (O) or 1 (P), never N.
     * Used for MIN (AND) and MAX (OR) on Bit-typed signals.
     */
    public Logic3 evalBit(Logic3... in) {
        int[] vals = new int[arity];
        int[] unk = new int[arity];
        int nu = 0;
        for (int i = 0; i < arity; i++) { if (in[i].isLevel()) vals[i] = in[i].bal(); else unk[nu++] = i; }
        int first = Integer.MIN_VALUE;
        for (int c = 0; c < (1 << nu); c++) {
            for (int j = 0; j < nu; j++) vals[unk[j]] = (c >> j) & 1;
            int r = fn.apply(vals);
            if (first == Integer.MIN_VALUE) first = r; else if (r != first) return Logic3.X;
        }
        return Logic3.ofBal(first);
    }

    /**
     * Exact X-extension: replace every non-level input by "any level", evaluate all completions,
     * return the level if all agree, else X.
     */
    public Logic3 eval(Logic3... in) {
        if (in.length != arity) throw new IllegalArgumentException(name() + " needs " + arity + " inputs");
        int[] vals = new int[arity];
        int[] unk = new int[arity];
        int nu = 0;
        for (int i = 0; i < arity; i++) {
            if (in[i].isLevel()) vals[i] = in[i].bal(); else unk[nu++] = i;
        }
        if (nu == 0) return Logic3.ofBal(fn.apply(vals));
        int total = 1;
        for (int i = 0; i < nu; i++) total *= 3;
        int first = Integer.MIN_VALUE;
        for (int c = 0; c < total; c++) {
            int m = c;
            for (int j = 0; j < nu; j++) { vals[unk[j]] = m % 3 - 1; m /= 3; }
            int r = fn.apply(vals);
            if (first == Integer.MIN_VALUE) first = r; else if (r != first) return Logic3.X;
        }
        return Logic3.ofBal(first);
    }
}
