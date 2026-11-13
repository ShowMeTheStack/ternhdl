package org.ternhdl.map;

import org.ternhdl.core.*;

/** Generic ternary (GT) cell library. Area values are a placeholder model (unary 1, binary 2, MUX3 3, DFF 4), independent of the SPICE characterization. */
public enum Cell {
    STI(1, 1), PTI(1, 1), NTI(1, 1), ICI(1, 1), DCI(1, 1), ISZERO(1, 1),
    MIN(2, 2), MAX(2, 2), ANY(2, 2), CONS(2, 2), SUM(2, 2),
    NMIN(2, 2), NMAX(2, 2), NANY(2, 2),
    /** (select, a, b, c): select N picks a, O picks b, P picks c. */
    MUX3(4, 3),
    TIE_N(0, 0), TIE_O(0, 0), TIE_P(0, 0),
    DFF(1, 4);

    public final int arity;
    public final int area;

    Cell(int arity, int area) { this.arity = arity; this.area = area; }

    public boolean isTie() { return this == TIE_N || this == TIE_O || this == TIE_P; }

    /** Level function on balanced values (combinational cells). */
    public int level(int... a) {
        return switch (this) {
            case STI -> Prim.STI.level(a); case PTI -> Prim.PTI.level(a); case NTI -> Prim.NTI.level(a);
            case ICI -> Prim.ICI.level(a); case DCI -> Prim.DCI.level(a); case ISZERO -> Prim.ISZERO.level(a);
            case MIN -> Prim.MIN.level(a); case MAX -> Prim.MAX.level(a); case ANY -> Prim.ANY.level(a);
            case CONS -> Prim.CONS.level(a); case SUM -> Prim.SUM.level(a);
            case NMIN -> -Prim.MIN.level(a); case NMAX -> -Prim.MAX.level(a); case NANY -> -Prim.ANY.level(a);
            case MUX3 -> Prim.MUX3.level(a);
            case TIE_N -> -1; case TIE_O -> 0; case TIE_P -> 1;
            case DFF -> throw new IllegalStateException("DFF has no combinational function");
        };
    }

    /** Exact X-extension (same rule as Prim.eval). */
    public Logic3 eval(Logic3... in) {
        int[] vals = new int[arity];
        int[] unk = new int[arity];
        int nu = 0;
        for (int i = 0; i < arity; i++) { if (in[i].isLevel()) vals[i] = in[i].bal(); else unk[nu++] = i; }
        int total = 1;
        for (int i = 0; i < nu; i++) total *= 3;
        int first = Integer.MIN_VALUE;
        for (int c = 0; c < total; c++) {
            int m = c;
            for (int j = 0; j < nu; j++) { vals[unk[j]] = m % 3 - 1; m /= 3; }
            int r = level(vals);
            if (first == Integer.MIN_VALUE) first = r; else if (r != first) return Logic3.X;
        }
        return Logic3.ofBal(first);
    }

    public static Cell of(Prim p) {
        return switch (p) {
            case STI -> STI; case PTI -> PTI; case NTI -> NTI; case ICI -> ICI; case DCI -> DCI; case ISZERO -> ISZERO;
            case MIN -> MIN; case MAX -> MAX; case ANY -> ANY; case CONS -> CONS; case SUM -> SUM; case MUX3 -> MUX3;
            default -> throw new IllegalArgumentException("no cell for " + p);
        };
    }
}
