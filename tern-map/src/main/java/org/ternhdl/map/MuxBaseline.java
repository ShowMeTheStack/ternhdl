package org.ternhdl.map;

import java.util.*;

/**
 * Baseline B1: MUX-based synthesis in the style of earlier ternary synthesis work. Every two-input cell f(a, b) becomes
 * MUX3(a, g_N(b), g_O(b), g_P(b)) where g_v(b) = f(v, b) are unary functions built from the unary database.
 */
public final class MuxBaseline {
    private MuxBaseline() { }

    public static Netlist of(Netlist in) {
        Netlist out = new Netlist(in.name);
        int[] map = new int[in.netCount()];
        Arrays.fill(map, -1);
        for (var en : in.inputs.entrySet()) {
            int[] nn = out.addInput(en.getKey(), en.getValue().length, in.isBit(en.getKey()));
            for (int i = 0; i < nn.length; i++) map[en.getValue()[i]] = nn[i];
        }
        List<int[]> dffs = new ArrayList<>();
        int dk = 0;
        for (int k = 0; k < in.insts.size(); k++) {
            Netlist.Inst i = in.insts.get(k);
            if (i.cell == Cell.DFF) { int ni = out.dff(in.dffNames.get(dk++)); map[i.out] = out.dffOut(ni); dffs.add(new int[]{k, ni}); }
        }
        Map<Integer, Map<Integer, Integer>> memo = new HashMap<>();
        for (Netlist.Inst i : in.insts) {
            if (i.cell == Cell.DFF) continue;
            if (i.cell.isTie()) { map[i.out] = out.tie(org.ternhdl.core.Logic3.ofBal(i.cell.level())); continue; }
            int[] a = new int[i.in.length];
            for (int j = 0; j < a.length; j++) a[j] = map[i.in[j]];
            if (i.cell.arity == 2) {
                int[] g = new int[3];
                for (int v = -1; v <= 1; v++) {
                    final int vv = v;
                    int code = UnaryDb.code(i.cell.level(vv, -1), i.cell.level(vv, 0), i.cell.level(vv, 1));
                    g[v + 1] = UnaryDb.build(out, a[1], code, memo.computeIfAbsent(a[1], x -> new HashMap<>()));
                }
                map[i.out] = out.add(Cell.MUX3, a[0], g[0], g[1], g[2]);
            } else map[i.out] = out.add(i.cell, a);
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
