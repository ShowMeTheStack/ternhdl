package org.ternhdl.map;

import java.util.*;

/**
 * Minimal-area circuits for all 27 unary ternary functions from GT cells (tree cost), including constants and binary
 * cells applied to two unary functions of the same input. Function code: f(N) + 3 f(O) + 9 f(P) in index form.
 */
public final class UnaryDb {
    public record Recipe(Cell cell, int[] args) { }   // args are function codes; cell null = identity

    public static final int IDENTITY = 0 + 3 * 1 + 9 * 2;
    private static final int[] COST = new int[27];
    private static final Recipe[] RECIPE = new Recipe[27];

    static {
        Arrays.fill(COST, Integer.MAX_VALUE);
        COST[IDENTITY] = 0;
        RECIPE[IDENTITY] = new Recipe(null, new int[0]);
        for (Cell t : new Cell[]{Cell.TIE_N, Cell.TIE_O, Cell.TIE_P}) {
            int v = t.level() + 1;
            int code = v + 3 * v + 9 * v;
            COST[code] = 0;
            RECIPE[code] = new Recipe(t, new int[0]);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Cell c : Cell.values()) {
                if (c.arity == 0 || c == Cell.DFF || c == Cell.MUX3) continue;
                for (int f = 0; f < 27; f++) {
                    if (COST[f] == Integer.MAX_VALUE) continue;
                    if (c.arity == 1) changed |= relax(c, new int[]{f});
                    else for (int g = 0; g < 27; g++) if (COST[g] != Integer.MAX_VALUE) changed |= relax(c, new int[]{f, g});
                }
            }
        }
    }

    private static int digit(int code, int k) { return code / (k == 0 ? 1 : k == 1 ? 3 : 9) % 3; }

    private static boolean relax(Cell c, int[] args) {
        int code = 0, cost = c.area;
        for (int a : args) cost += COST[a];
        for (int k = 0; k < 3; k++) {
            int[] bal = new int[args.length];
            for (int j = 0; j < args.length; j++) bal[j] = digit(args[j], k) - 1;
            code += (c.level(bal) + 1) * (k == 0 ? 1 : k == 1 ? 3 : 9);
        }
        if (cost < COST[code]) { COST[code] = cost; RECIPE[code] = new Recipe(c, args); return true; }
        return false;
    }

    public static int cost(int code) { return COST[code]; }
    public static Recipe recipe(int code) { return RECIPE[code]; }

    /** Code from a table indexed by input level N, O, P (values -1..1). */
    public static int code(int fN, int fO, int fP) { return (fN + 1) + 3 * (fO + 1) + 9 * (fP + 1); }

    /** Instantiates function code applied to net x, sharing sub-results through memo. */
    public static int build(Netlist nl, int x, int code, Map<Integer, Integer> memo) {
        Integer have = memo.get(code);
        if (have != null) return have;
        Recipe r = RECIPE[code];
        int out;
        if (r.cell() == null) out = x;
        else if (r.cell().isTie()) out = nl.tie(org.ternhdl.core.Logic3.ofBal(r.cell().level()));
        else {
            int[] in = new int[r.args().length];
            for (int j = 0; j < in.length; j++) in[j] = build(nl, x, r.args()[j], memo);
            out = nl.add(r.cell(), in);
        }
        memo.put(code, out);
        return out;
    }

    /** Cheapest code among the given candidates (used for don't-care entries). */
    public static int cheapest(int... codes) {
        int best = codes[0];
        for (int c : codes) if (COST[c] < COST[best]) best = c;
        return best;
    }

    private UnaryDb() { }
}
