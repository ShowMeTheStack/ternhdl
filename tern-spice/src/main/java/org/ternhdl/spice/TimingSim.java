package org.ternhdl.spice;

import java.util.*;
import org.ternhdl.core.Logic3;
import org.ternhdl.map.*;

/**
 * Event-driven timing simulation of a combinational GT netlist with characterized delays. Inertial delay (a pending
 * output change is cancelled when the cell re-evaluates first). A net that changes N<->P passes through O for the
 * characterized dwell, so non-monotonic cells downstream can glitch.
 */
public final class TimingSim {
    public record VectorResult(double settle, int[] transitions) { }   // per output digit: number of level changes

    private final Netlist nl;
    private final TimingLib lib;
    private final int[] val;
    private final double[] fanout;
    private final List<List<int[]>> readers = new ArrayList<>();   // net -> {instIndex, pin}
    private final double inputPassThrough;

    public TimingSim(Netlist nl, TimingLib lib, double inputRise) { this(nl, lib, inputRise, new double[0]); }

    /** extraFanout[net]: additional load per net in units of one input capacitance (e.g. wire C / cIn). */
    public TimingSim(Netlist nl, TimingLib lib, double inputRise, double[] extraFanout) {
        this.nl = nl; this.lib = lib;
        int n = nl.netCount();
        val = new int[n];
        fanout = new double[n];
        for (int i = 0; i < n; i++) readers.add(new ArrayList<>());
        for (int k = 0; k < nl.insts().size(); k++) {
            Netlist.Inst in = nl.insts().get(k);
            if (in.cell == Cell.DFF) throw new IllegalArgumentException("combinational only");
            for (int p = 0; p < in.in.length; p++) { readers.get(in.in[p]).add(new int[]{k, p}); fanout[in.in[p]]++; }
        }
        for (int[] o : nl.outputs().values()) for (int x : o) fanout[x]++;
        for (int i = 0; i < extraFanout.length && i < n; i++) fanout[i] += extraFanout[i];
        inputPassThrough = inputRise / 2;
    }

    private record Ev(double t, int net, int v, long id) { }

    /** Settles all nets for the given input values (zero-delay), used for the initial vector. */
    public void init(Map<String, Logic3[]> inputs) {
        for (var en : nl.inputs().entrySet()) for (int i = 0; i < en.getValue().length; i++) val[en.getValue()[i]] = inputs.get(en.getKey())[i].bal();
        for (Netlist.Inst in : nl.insts()) val[in.out] = eval(in);
    }

    private int eval(Netlist.Inst in) {
        int[] a = new int[in.in.length];
        for (int j = 0; j < a.length; j++) a[j] = val[in.in[j]];
        return in.cell.level(a);
    }

    /** Applies a new input vector at time 0 and simulates until quiet. */
    public VectorResult apply(Map<String, Logic3[]> inputs) {
        PriorityQueue<Ev> q = new PriorityQueue<>(Comparator.comparingDouble(Ev::t).thenComparingLong(Ev::id));
        long[] seq = {0};
        Map<Integer, List<Ev>> pending = new HashMap<>();
        Set<Long> cancelled = new HashSet<>();
        for (var en : nl.inputs().entrySet()) for (int i = 0; i < en.getValue().length; i++) {
            int net = en.getValue()[i], nv = inputs.get(en.getKey())[i].bal();
            if (nv == val[net]) continue;
            if (Math.abs(nv - val[net]) == 2) { q.add(new Ev(0, net, 0, seq[0]++)); q.add(new Ev(inputPassThrough, net, nv, seq[0]++)); }
            else q.add(new Ev(0, net, nv, seq[0]++));
        }
        Map<Integer, Integer> outIndex = new HashMap<>();
        int idx = 0;
        for (int[] o : nl.outputs().values()) for (int x : o) outIndex.putIfAbsent(x, idx++);
        int[] trans = new int[idx];
        double last = 0;
        while (!q.isEmpty()) {
            Ev e = q.poll();
            if (cancelled.remove(e.id())) continue;
            List<Ev> pl = pending.get(e.net());
            if (pl != null) pl.removeIf(p -> p.id() == e.id());
            if (val[e.net()] == e.v()) continue;
            int old = val[e.net()];
            val[e.net()] = e.v();
            Integer oi = outIndex.get(e.net());
            if (oi != null) { trans[oi]++; last = Math.max(last, e.t()); }
            for (int[] r : readers.get(e.net())) {
                Netlist.Inst in = nl.insts().get(r[0]);
                int nv = eval(in);
                List<Ev> pend = pending.computeIfAbsent(in.out, k -> new ArrayList<>());
                for (Ev p : pend) cancelled.add(p.id());       // inertial: re-evaluation replaces pending changes
                pend.clear();
                int cur = val[in.out];
                if (nv == cur) continue;
                double d = lib.delay(in.cell, r[1], old, e.v(), fanout[in.out]);
                if (Math.abs(nv - cur) == 2) {
                    Ev a = new Ev(e.t() + d, in.out, 0, seq[0]++), b = new Ev(e.t() + d + lib.meanPassDwell, in.out, nv, seq[0]++);
                    q.add(a); q.add(b); pend.add(a); pend.add(b);
                } else { Ev a = new Ev(e.t() + d, in.out, nv, seq[0]++); q.add(a); pend.add(a); }
            }
        }
        return new VectorResult(last, trans);
    }

    /** Static timing: worst-case arrival at every net (topological), worst arc delay per cell at its fan-out. */
    public static double sta(Netlist nl, TimingLib lib) { return sta(nl, lib, new double[0]); }

    public static double sta(Netlist nl, TimingLib lib, double[] extraFanout) {
        double[] arr = new double[nl.netCount()];
        double[] fo = new double[nl.netCount()];
        for (int i = 0; i < extraFanout.length && i < fo.length; i++) fo[i] += extraFanout[i];
        for (Netlist.Inst in : nl.insts()) for (int x : in.in) fo[x]++;
        for (int[] o : nl.outputs().values()) for (int x : o) fo[x]++;
        double worst = 0;
        for (Netlist.Inst in : nl.insts()) {
            if (in.cell.isTie() || in.cell == Cell.DFF) continue;
            double a = 0;
            for (int x : in.in) a = Math.max(a, arr[x]);
            arr[in.out] = a + lib.worst(in.cell, fo[in.out]) + lib.meanPassDwell;
        }
        for (int[] o : nl.outputs().values()) for (int x : o) worst = Math.max(worst, arr[x]);
        return worst;
    }
}
