package org.ternhdl.map;

import java.util.*;
import org.ternhdl.core.Logic3;

/**
 * A ternary gate-level netlist. Nets are integers; every net has exactly one driver (an input digit, a cell output or
 * a DFF output). Combinational instances are kept in topological order; DFF outputs are sources.
 */
public final class Netlist {
    public static final class Inst {
        public final Cell cell;
        public final int[] in;
        public final int out;
        Inst(Cell cell, int[] in, int out) { this.cell = cell; this.in = in; this.out = out; }
    }

    public final String name;
    private int nets;
    final List<Inst> insts = new ArrayList<>();
    final LinkedHashMap<String, int[]> inputs = new LinkedHashMap<>();
    final LinkedHashMap<String, int[]> outputs = new LinkedHashMap<>();
    final Set<String> bitPorts = new HashSet<>();
    final List<String> dffNames = new ArrayList<>();
    private final EnumMap<Cell, Integer> ties = new EnumMap<>(Cell.class);

    public Netlist(String name) { this.name = name; }

    public int newNet() { return nets++; }
    public int netCount() { return nets; }
    public List<Inst> insts() { return Collections.unmodifiableList(insts); }
    public Map<String, int[]> inputs() { return Collections.unmodifiableMap(inputs); }
    public Map<String, int[]> outputs() { return Collections.unmodifiableMap(outputs); }
    public boolean isBit(String port) { return bitPorts.contains(port); }

    public int[] addInput(String nm, int width, boolean bit) {
        int[] n = new int[width];
        for (int i = 0; i < width; i++) n[i] = newNet();
        inputs.put(nm, n);
        if (bit) bitPorts.add(nm);
        return n;
    }

    public void addOutput(String nm, int[] nets, boolean bit) { outputs.put(nm, nets.clone()); if (bit) bitPorts.add(nm); }

    public int add(Cell c, int... in) {
        if (c == Cell.DFF) throw new IllegalArgumentException("use dff()");
        if (in.length != c.arity) throw new IllegalArgumentException(c + " arity");
        int o = newNet();
        insts.add(new Inst(c, in.clone(), o));
        return o;
    }

    public int tie(Logic3 v) {
        Cell c = v == Logic3.N ? Cell.TIE_N : v == Logic3.P ? Cell.TIE_P : Cell.TIE_O;
        return ties.computeIfAbsent(c, k -> add(k));
    }

    /** Creates a DFF whose D input is set later with setD; returns the instance index. */
    public int dff(String nm) {
        int o = newNet();
        insts.add(new Inst(Cell.DFF, new int[]{-1}, o));
        dffNames.add(nm);
        return insts.size() - 1;
    }

    public int dffOut(int idx) { return insts.get(idx).out; }
    public void setD(int idx, int net) { insts.get(idx).in[0] = net; }

    /** Copy without logic that reaches no output and no DFF input (DFFs, ports and DFF order are kept). */
    public Netlist sweep() {
        boolean[] need = new boolean[nets];
        int[] driverIdx = new int[nets];
        Arrays.fill(driverIdx, -1);
        for (int k = 0; k < insts.size(); k++) driverIdx[insts.get(k).out] = k;
        Deque<Integer> work = new ArrayDeque<>();
        for (int[] o : outputs.values()) for (int x : o) work.add(x);
        for (Inst i : insts) if (i.cell == Cell.DFF) work.add(i.in[0]);
        while (!work.isEmpty()) {
            int n = work.pop();
            if (n < 0 || need[n]) continue;
            need[n] = true;
            int k = driverIdx[n];
            if (k >= 0 && insts.get(k).cell != Cell.DFF) for (int x : insts.get(k).in) work.add(x);
        }
        Netlist out = new Netlist(name);
        int[] map = new int[nets];
        Arrays.fill(map, -1);
        for (var en : inputs.entrySet()) {
            int[] nn = out.addInput(en.getKey(), en.getValue().length, bitPorts.contains(en.getKey()));
            for (int i = 0; i < nn.length; i++) map[en.getValue()[i]] = nn[i];
        }
        List<int[]> dffs = new ArrayList<>();
        int dk = 0;
        for (int k = 0; k < insts.size(); k++) {
            Inst i = insts.get(k);
            if (i.cell == Cell.DFF) { int ni = out.dff(dffNames.get(dk++)); map[i.out] = out.dffOut(ni); dffs.add(new int[]{k, ni}); }
        }
        for (Inst i : insts) {
            if (i.cell == Cell.DFF || !need[i.out]) continue;
            if (i.cell.isTie()) { map[i.out] = out.tie(org.ternhdl.core.Logic3.ofBal(i.cell.level())); continue; }
            int[] a = new int[i.in.length];
            for (int j = 0; j < a.length; j++) a[j] = map[i.in[j]];
            map[i.out] = out.add(i.cell, a);
        }
        for (int[] d : dffs) out.setD(d[1], map[insts.get(d[0]).in[0]]);
        for (var en : outputs.entrySet()) {
            int[] o = new int[en.getValue().length];
            for (int i = 0; i < o.length; i++) o[i] = map[en.getValue()[i]];
            out.addOutput(en.getKey(), o, bitPorts.contains(en.getKey()));
        }
        return out;
    }

    // ---------------------------------------------------------------- metrics
    public int cellCount() { int c = 0; for (Inst i : insts) if (!i.cell.isTie() && i.cell != Cell.DFF) c++; return c; }
    public int dffCount() { int c = 0; for (Inst i : insts) if (i.cell == Cell.DFF) c++; return c; }
    public int area() { int a = 0; for (Inst i : insts) a += i.cell.area; return a; }

    /** Longest combinational path in cells (ties and DFF outputs have depth 0). */
    public int depth() {
        int[] dep = new int[nets];
        int max = 0;
        for (Inst i : insts) {
            if (i.cell == Cell.DFF || i.cell.isTie()) continue;
            int d = 0;
            for (int x : i.in) d = Math.max(d, dep[x]);
            dep[i.out] = d + 1;
            max = Math.max(max, d + 1);
        }
        return max;
    }

    public Map<Cell, Integer> histogram() {
        Map<Cell, Integer> h = new EnumMap<>(Cell.class);
        for (Inst i : insts) h.merge(i.cell, 1, Integer::sum);
        return h;
    }

    public String stats() {
        return String.format("cells=%d dff=%d depth=%d area=%d %s", cellCount(), dffCount(), depth(), area(), histogram());
    }
}
