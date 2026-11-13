package org.ternhdl.spice;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.ternhdl.core.*;
import org.ternhdl.map.*;
import org.ternhdl.stdlib.Benchmarks;

/** SPICE vs event-driven timing simulation vs STA on mapped combinational benchmarks (not a test). */
public final class SpiceCompare {
    public static void main(String[] args) throws Exception {
        DeviceParams p = DeviceParams.nominal();
        TimingLib lib = new TimingLib(Path.of("cells/gt-param-nominal.json"));
        ExactDb db = new ExactDb(ExactDb.gtFull());
        List<String> names = List.of("ripple9", "csel9", "prefix9", "satadd9", "alu9", "dot4x9", "tritsum27", "cmp27", "prefix27", "mul9");
        ExecutorService ex = Executors.newFixedThreadPool(10);
        Map<String, Future<String>> fs = new LinkedHashMap<>();
        for (String nm : names) fs.put(nm, ex.submit(() -> one(nm, p, lib, db)));
        System.out.println("| Design | cells | depth | vectors | SPICE settle mean/max (ps) | event-sim settle mean/max (ps) | mean abs err (ps) | corr | STA (ps) | STA >= SPICE max | extra transitions SPICE / event-sim | digit transition-count agreement |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (var f : fs.entrySet()) { String r; try { r = f.getValue().get(); } catch (ExecutionException e) { r = "| " + f.getKey() + " | ERROR " + e.getCause() + " |"; } System.out.println(r); }
        ex.shutdown();
    }

    static String one(String nm, DeviceParams p, TimingLib lib, ExactDb db) throws Exception {
        Design rtl = Benchmarks.all().get(nm).get();
        Netlist nl = Mapper.mapToFixpoint(Lower.lower(rtl).netlist().sweep(), db);
        return compare(nm, "spcmp-", nl, p, lib, Map.of(), Double.NaN);
    }

    /** SPICE vs event-sim vs STA for one netlist; wireCap (F per net) is added to SPICE and as extra load to timing. */
    public static String compare(String nm, String tagPrefix, Netlist nl, DeviceParams p, TimingLib lib, Map<Integer, Double> wireCap, double openSta) throws Exception {
        double[] extra = new double[nl.netCount()];
        for (var e : wireCap.entrySet()) if (e.getKey() < extra.length) extra[e.getKey()] = e.getValue() / p.cIn();
        Random r = new Random(nm.hashCode());
        int nv = 30;
        List<Map<String, Logic3[]>> vecs = new ArrayList<>();
        for (int k = 0; k < nv; k++) {
            Map<String, Logic3[]> m = new LinkedHashMap<>();
            for (var en : nl.inputs().entrySet()) {
                Logic3[] v = new Logic3[en.getValue().length];
                for (int i = 0; i < v.length; i++) v[i] = nl.isBit(en.getKey()) ? (r.nextBoolean() ? Logic3.P : Logic3.O) : Logic3.ofBal(r.nextInt(3) - 1);
                m.put(en.getKey(), v);
            }
            vecs.add(m);
        }
        double sta = TimingSim.sta(nl, lib, extra);
        double period = Math.ceil((1.5 * sta + 100e-12) / 1e-11) * 1e-11, tr = 20e-12;
        List<String> probes = new ArrayList<>();
        List<Integer> outNets = new ArrayList<>();
        for (int[] o : nl.outputs().values()) for (int x : o) if (!outNets.contains(x)) { outNets.add(x); probes.add("v(n" + x + ")"); }
        Path dir = Ngspice.workRoot().resolve(tagPrefix + nm);
        Ngspice.run(dir, SpiceNetlist.deck(nl, p, vecs, period, tr, 1e-12, probes, wireCap), 3600);
        double[][] w = Ngspice.read(dir.resolve("out.txt"), probes.size());
        TimingSim ts = new TimingSim(nl, lib, tr, extra);
        ts.init(vecs.get(0));
        double sumS = 0, sumE = 0, maxS = 0, maxE = 0, sumErr = 0;
        double[] xs = new double[nv - 1], ys = new double[nv - 1];
        int extraS = 0, extraE = 0, agree = 0, digits = 0;
        for (int k = 1; k < nv; k++) {
            double t0 = k * period, t1 = (k + 1) * period;
            TimingSim.VectorResult er = ts.apply(vecs.get(k));
            double spiceSettle = 0;
            int[] tS = new int[outNets.size()];
            for (int j = 0; j < outNets.size(); j++) {
                Logic3 prev = null, start = null;
                for (double[] row : w) {
                    if (row[0] < t0 - 1e-15 || row[0] >= t1) continue;
                    Logic3 q = SpiceNetlist.quantize(p, row[j + 1]);
                    if (start == null) start = q;
                    if (prev != null && q != prev) { tS[j]++; spiceSettle = Math.max(spiceSettle, row[0] - t0); }
                    prev = q;
                }
                int minimal = start == null || prev == null ? 0 : Math.abs(start.bal() - prev.bal()) == 2 ? 2 : start == prev ? 0 : 1;
                extraS += tS[j] - minimal;
                // event-sim transitions for the same net (outputs are indexed in the same first-seen order)
                int te = er.transitions()[j];
                extraE += te - minimal;
                if (te == tS[j]) agree++;
                digits++;
            }
            xs[k - 1] = spiceSettle; ys[k - 1] = er.settle();
            sumS += spiceSettle; sumE += er.settle(); maxS = Math.max(maxS, spiceSettle); maxE = Math.max(maxE, er.settle());
            sumErr += Math.abs(spiceSettle - er.settle());
        }
        int n = nv - 1;
        double mx = sumS / n, my = sumE / n, sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx); syy += (ys[i] - my) * (ys[i] - my); }
        double corr = sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : Double.NaN;
        return String.format(Locale.ROOT, "| %s | %d | %d | %d | %.1f / %.1f | %.1f / %.1f | %.1f | %.2f | %.1f | %s | %d / %d | %.1f%% |%s",
            nm, nl.cellCount(), nl.depth(), n, mx * 1e12, maxS * 1e12, my * 1e12, maxE * 1e12, sumErr / n * 1e12, corr, sta * 1e12,
            sta >= maxS ? "yes" : "NO", extraS, extraE, 100.0 * agree / digits, Double.isNaN(openSta) ? "" : String.format(Locale.ROOT, " %.1f |", openSta));
    }
}
