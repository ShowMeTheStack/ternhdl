package org.ternhdl.map;

import java.util.*;
import java.util.stream.IntStream;

/**
 * Minimal tree-area circuits for all 3^9 = 19,683 two-input ternary functions over a chosen set of 1- and 2-input
 * cells (MUX3 excluded), computed by a cost-bucketed Dijkstra over function codes. Function code: sum over
 * (a, b) of f(a, b) * 3^(3a + b) in index form (N=0, O=1, P=2), a = first input x, b = second input y.
 * Tie cells (constants) cost 0 and are always available. Tree cost counts shared sub-circuits once per use.
 */
public final class ExactDb {
    public static final int N = 19683;
    public record Recipe(Cell cell, int a, int b) { }   // cell null: leaf (x, y or constant); b = -1 for unary cells

    private static final byte[][] DIG = new byte[N][9];
    private static final int[] POW3 = new int[9];
    static {
        POW3[0] = 1; for (int i = 1; i < 9; i++) POW3[i] = POW3[i - 1] * 3;
        for (int c = 0; c < N; c++) { int m = c; for (int p = 0; p < 9; p++) { DIG[c][p] = (byte) (m % 3); m /= 3; } }
    }

    public static final int X, Y;
    static {
        int x = 0, y = 0;
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) { x += a * POW3[3 * a + b]; y += b * POW3[3 * a + b]; }
        X = x; Y = y;
    }

    public static int constant(int idx) { int k = 0; for (int p = 0; p < 9; p++) k += idx * POW3[p]; return k; }

    public final List<Cell> cells;
    public final int[] cost = new int[N];
    public final Recipe[] recipe = new Recipe[N];
    public final double seconds;

    public ExactDb(List<Cell> cells) {
        long t0 = System.nanoTime();
        this.cells = List.copyOf(cells);
        Arrays.fill(cost, Integer.MAX_VALUE);
        int[][] tab1 = new int[Cell.values().length][], tab2 = new int[Cell.values().length][];
        for (Cell c : cells) {
            if (c.arity == 1) { tab1[c.ordinal()] = new int[3]; for (int a = 0; a < 3; a++) tab1[c.ordinal()][a] = c.level(a - 1) + 1; }
            else if (c.arity == 2) { tab2[c.ordinal()] = new int[9]; for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) tab2[c.ordinal()][a * 3 + b] = c.level(a - 1, b - 1) + 1; }
        }
        boolean[] done = new boolean[N];
        List<Integer> finalized = new ArrayList<>();
        // leaves
        set(X, 0, new Recipe(null, X, -1)); set(Y, 0, new Recipe(null, Y, -1));
        for (int v = 0; v < 3; v++) set(constant(v), 0, new Recipe(null, constant(v), -1));
        int maxCost = 0;
        for (Cell c : cells) maxCost += c.area;
        for (int level = 0; finalized.size() < N && level < 200; level++) {
            final int lvl = level;
            List<Integer> frontier = new ArrayList<>();
            for (int f = 0; f < N; f++) if (!done[f] && cost[f] == lvl) frontier.add(f);
            if (frontier.isEmpty()) continue;
            for (int f : frontier) done[f] = true;
            finalized.addAll(frontier);
            final int[] fin = finalized.stream().mapToInt(Integer::intValue).toArray();
            final int[] fr = frontier.stream().mapToInt(Integer::intValue).toArray();
            // per-thread candidate improvements, merged after the bucket
            record Cand(int f, int cost, Recipe r) { }
            List<Cand> cands = Collections.synchronizedList(new ArrayList<>());
            IntStream.range(0, fr.length).parallel().forEach(ii -> {
                int f = fr[ii];
                int[] lc = new int[0];
                Map<Integer, Cand> local = new HashMap<>();
                for (Cell c : this.cells) {
                    if (c.arity == 1) {
                        int[] t = tab1[c.ordinal()];
                        int h = 0;
                        for (int p = 0; p < 9; p++) h += t[DIG[f][p]] * POW3[p];
                        int nc = lvl + c.area;
                        Cand old = local.get(h);
                        if (nc < cost[h] && (old == null || nc < old.cost())) local.put(h, new Cand(h, nc, new Recipe(c, f, -1)));
                    } else if (c.arity == 2) {
                        int[] t = tab2[c.ordinal()];
                        for (int g : fin) {
                            int nc = lvl + cost[g] + c.area;
                            int h = 0;
                            byte[] df = DIG[f], dg = DIG[g];
                            for (int p = 0; p < 9; p++) h += t[df[p] * 3 + dg[p]] * POW3[p];
                            if (nc >= cost[h]) continue;
                            Cand old = local.get(h);
                            if (old == null || nc < old.cost()) local.put(h, new Cand(h, nc, new Recipe(c, f, g)));
                            if (!commutative(t)) {
                                int h2 = 0;
                                for (int p = 0; p < 9; p++) h2 += t[dg[p] * 3 + df[p]] * POW3[p];
                                if (nc < cost[h2]) { Cand o2 = local.get(h2); if (o2 == null || nc < o2.cost()) local.put(h2, new Cand(h2, nc, new Recipe(c, g, f))); }
                            }
                        }
                    }
                }
                cands.addAll(local.values());
            });
            // deterministic merge: thread arrival order must not decide ties (otherwise regfile9x9 maps to 262 to 265 cells depending on the run)
            cands.sort(Comparator.comparingInt(Cand::f).thenComparingInt(Cand::cost)
                .thenComparingInt(cd -> cd.r().cell().ordinal()).thenComparingInt(cd -> cd.r().a()).thenComparingInt(cd -> cd.r().b()));
            for (Cand cd : cands) if (!done[cd.f()] && cd.cost() < cost[cd.f()]) set(cd.f(), cd.cost(), cd.r());
        }
        seconds = (System.nanoTime() - t0) / 1e9;
    }

    private static boolean commutative(int[] t) {
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) if (t[a * 3 + b] != t[b * 3 + a]) return false;
        return true;
    }

    private void set(int f, int c, Recipe r) { cost[f] = c; recipe[f] = r; }

    public int reachable() { int n = 0; for (int c : cost) if (c != Integer.MAX_VALUE) n++; return n; }

    public int maxCost() { int m = 0; for (int c : cost) if (c != Integer.MAX_VALUE) m = Math.max(m, c); return m; }

    public double meanCost() { long s = 0; int n = 0; for (int c : cost) if (c != Integer.MAX_VALUE) { s += c; n++; } return (double) s / n; }

    public int[] histogram() { int[] h = new int[maxCost() + 1]; for (int c : cost) if (c != Integer.MAX_VALUE) h[c]++; return h; }

    /** Function code of f(x, y) given as a 3x3 table of balanced values tab[a+1][b+1]. */
    public static int code(int[][] tab) {
        int k = 0;
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) k += (tab[a][b] + 1) * POW3[3 * a + b];
        return k;
    }

    /** Value (index form) of function code f at inputs (a, b) in index form. */
    public static int at(int f, int a, int b) { return DIG[f][3 * a + b]; }

    /** Instantiates f(xNet, yNet) into the netlist, sharing sub-results through memo (code -> net). */
    public int build(Netlist nl, int xNet, int yNet, int f, Map<Integer, Integer> memo) {
        Integer have = memo.get(f);
        if (have != null) return have;
        Recipe r = recipe[f];
        int out;
        if (r.cell() == null) {
            if (f == X) out = xNet; else if (f == Y) out = yNet;
            else out = nl.tie(org.ternhdl.core.Logic3.ofBal(DIG[f][0] - 1));
        } else if (r.b() < 0) out = nl.add(r.cell(), build(nl, xNet, yNet, r.a(), memo));
        else out = nl.add(r.cell(), build(nl, xNet, yNet, r.a(), memo), build(nl, xNet, yNet, r.b(), memo));
        memo.put(f, out);
        return out;
    }

    /** Standard libraries for ablations. */
    public static List<Cell> gtFull() {
        return List.of(Cell.STI, Cell.PTI, Cell.NTI, Cell.ICI, Cell.DCI, Cell.ISZERO, Cell.MIN, Cell.MAX, Cell.ANY, Cell.CONS, Cell.SUM, Cell.NMIN, Cell.NMAX, Cell.NANY);
    }
}
