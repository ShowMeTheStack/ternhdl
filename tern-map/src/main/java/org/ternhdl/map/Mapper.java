package org.ternhdl.map;

import java.util.*;
import org.ternhdl.core.Logic3;

/**
 * Cut-based technology mapping with the exact-synthesis database. Cuts have at most two leaves (the database covers
 * two-input functions); every combinational instance also keeps its "native" cut (its own fan-ins, implemented by
 * the original cell), so multi-input cells such as MUX3 stay mappable. Cover selection by area flow.
 */
public final class Mapper {
    private record Cut(int[] leaves, boolean nativeCut) { }

    private final Netlist in;
    private final ExactDb db;
    private final int[] driver;          // net -> comb instance index, or -1 for sources
    private final Cell[] driverCell;
    private final int[] fanout;
    private final List<List<Cut>> cuts = new ArrayList<>();
    private final double[] flow;
    private final Cut[] best;
    private final int[] bestCode;
    private static final int MAX_CUTS = 16;

    private Mapper(Netlist in, ExactDb db) {
        this.in = in; this.db = db;
        int n = in.netCount();
        driver = new int[n]; Arrays.fill(driver, -1);
        driverCell = new Cell[n];
        fanout = new int[n];
        flow = new double[n];
        best = new Cut[n];
        bestCode = new int[n];
        for (int k = 0; k < in.insts.size(); k++) {
            Netlist.Inst i = in.insts.get(k);
            driverCell[i.out] = i.cell;
            if (i.cell != Cell.DFF && !i.cell.isTie()) driver[i.out] = k;
            for (int x : i.in) if (x >= 0) fanout[x]++;
        }
        for (int[] o : in.outputs.values()) for (int x : o) fanout[x]++;
        for (int x = 0; x < n; x++) cuts.add(null);
    }

    public static Netlist map(Netlist in, ExactDb db) { return new Mapper(in, db).run(); }

    /** Maps repeatedly until the cell count stops improving (at most 4 rounds). */
    public static Netlist mapToFixpoint(Netlist in, ExactDb db) {
        Netlist cur = in;
        for (int r = 0; r < 4; r++) {
            Netlist next = map(cur, db);
            if (next.area() >= cur.area()) return cur.area() <= next.area() ? cur : next;
            cur = next;
        }
        return cur;
    }

    private boolean isTie(int net) { return driverCell[net] != null && driverCell[net].isTie(); }

    private List<Cut> cutsOf(int net) {
        List<Cut> c = cuts.get(net);
        if (c != null) return c;
        c = new ArrayList<>();
        if (isTie(net)) c.add(new Cut(new int[0], false));
        else if (driver[net] < 0) c.add(new Cut(new int[]{net}, false));
        else {
            Netlist.Inst inst = in.insts.get(driver[net]);
            c.add(new Cut(new int[]{net}, false));                 // trivial cut (used by fan-outs)
            c.add(new Cut(inst.in.clone(), true));                // native cut
            Set<String> seen = new HashSet<>();
            List<int[]> acc = new ArrayList<>();
            acc.add(new int[0]);
            for (int fi : inst.in) {
                List<int[]> nx = new ArrayList<>();
                for (int[] partial : acc) for (Cut fc : cutsOf(fi)) {
                    if (fc.nativeCut()) continue;
                    int[] u = union(partial, fc.leaves());
                    if (u != null) nx.add(u);
                }
                acc = nx;
                if (acc.size() > 64) acc = acc.subList(0, 64);
            }
            for (int[] u : acc) if (seen.add(Arrays.toString(u)) && !(u.length == 1 && u[0] == net)) c.add(new Cut(u, false));
            if (c.size() > MAX_CUTS) c = new ArrayList<>(c.subList(0, MAX_CUTS));
        }
        cuts.set(net, c);
        return c;
    }

    private static int[] union(int[] a, int[] b) {
        TreeSet<Integer> s = new TreeSet<>();
        for (int x : a) s.add(x);
        for (int x : b) s.add(x);
        if (s.size() > 2) return null;
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Value (balanced) of net under leaf assignment. */
    private int eval(int net, Map<Integer, Integer> leafVal, Map<Integer, Integer> memo) {
        Integer v = leafVal.get(net);
        if (v != null) return v;
        Integer m = memo.get(net);
        if (m != null) return m;
        int r;
        if (isTie(net)) r = driverCell[net].level();
        else {
            int k = driver[net];
            if (k < 0) throw new IllegalStateException("net " + net + " is not inside the cut");
            Netlist.Inst inst = in.insts.get(k);
            int[] a = new int[inst.in.length];
            for (int j = 0; j < a.length; j++) a[j] = eval(inst.in[j], leafVal, memo);
            r = inst.cell.level(a);
        }
        memo.put(net, r);
        return r;
    }

    /** ExactDb code of net as a function of the cut leaves (x = leaf 0, y = leaf 1; missing leaves ignored). */
    private int codeOf(int net, int[] leaves) {
        int[][] tab = new int[3][3];
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
            Map<Integer, Integer> lv = new HashMap<>();
            if (leaves.length > 0) lv.put(leaves[0], a - 1);
            if (leaves.length > 1) lv.put(leaves[1], b - 1);
            tab[a][b] = eval(net, lv, new HashMap<>());
        }
        return ExactDb.code(tab);
    }

    private double leafFlow(int leaf) { return flow[leaf] / Math.max(1, fanout[leaf]); }

    private Netlist run() {
        // area flow in topological order
        for (Netlist.Inst inst : in.insts) {
            if (inst.cell == Cell.DFF || inst.cell.isTie()) continue;
            int net = inst.out;
            double bestF = Double.MAX_VALUE;
            for (Cut c : cutsOf(net)) {
                if (!c.nativeCut() && c.leaves().length == 1 && c.leaves()[0] == net) continue;
                double f;
                int code = -1;
                if (c.nativeCut()) {
                    f = inst.cell.area;
                    for (int x : c.leaves()) f += leafFlow(x);
                } else {
                    code = codeOf(net, c.leaves());
                    f = db.cost[code];
                    for (int x : c.leaves()) f += leafFlow(x);
                }
                if (f < bestF - 1e-9) { bestF = f; best[net] = c; bestCode[net] = code; }
            }
            flow[net] = bestF;
        }
        // cover
        boolean[] req = new boolean[in.netCount()];
        Deque<Integer> work = new ArrayDeque<>();
        for (int[] o : in.outputs.values()) for (int x : o) work.add(x);
        for (Netlist.Inst inst : in.insts) if (inst.cell == Cell.DFF) work.add(inst.in[0]);
        while (!work.isEmpty()) {
            int net = work.pop();
            if (req[net]) continue;
            req[net] = true;
            if (driver[net] >= 0) for (int x : best[net].leaves()) work.add(x);
        }
        // rebuild
        Netlist out = new Netlist(in.name);
        int[] map = new int[in.netCount()];
        Arrays.fill(map, -1);
        for (var en : in.inputs.entrySet()) {
            int[] nn = out.addInput(en.getKey(), en.getValue().length, in.isBit(en.getKey()));
            for (int i = 0; i < nn.length; i++) map[en.getValue()[i]] = nn[i];
        }
        List<int[]> dffs = new ArrayList<>();   // {oldInstIndex, newInstIndex}
        int dk = 0;
        for (int k = 0; k < in.insts.size(); k++) {
            Netlist.Inst inst = in.insts.get(k);
            if (inst.cell == Cell.DFF) { int ni = out.dff(in.dffNames.get(dk++)); map[inst.out] = out.dffOut(ni); dffs.add(new int[]{k, ni}); }
            else if (inst.cell.isTie()) map[inst.out] = out.tie(Logic3.ofBal(inst.cell.level()));
        }
        Map<String, Map<Integer, Integer>> memo = new HashMap<>();
        for (Netlist.Inst inst : in.insts) {
            if (inst.cell == Cell.DFF || inst.cell.isTie() || !req[inst.out]) continue;
            int net = inst.out;
            Cut c = best[net];
            if (c.nativeCut()) {
                int[] a = new int[c.leaves().length];
                for (int j = 0; j < a.length; j++) a[j] = map[c.leaves()[j]];
                map[net] = out.add(inst.cell, a);
            } else {
                int[] l = c.leaves();
                int x = l.length > 0 ? map[l[0]] : out.tie(Logic3.O);
                int y = l.length > 1 ? map[l[1]] : x;
                map[net] = db.build(out, x, y, bestCode[net], memo.computeIfAbsent(x + "," + y, k -> new HashMap<>()));
            }
        }
        for (int[] d : dffs) out.setD(d[1], map[in.insts.get(d[0]).in[0]]);
        for (var en : in.outputs.entrySet()) {
            int[] o = new int[en.getValue().length];
            for (int i = 0; i < o.length; i++) o[i] = map[en.getValue()[i]];
            out.addOutput(en.getKey(), o, in.isBit(en.getKey()));
        }
        return out;
    }
}
