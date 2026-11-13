package org.ternhdl.spice;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.ternhdl.core.Logic3;
import org.ternhdl.map.Cell;

/** Characterizes GT cells under a DeviceParams: noise margins, timing arcs at several fan-outs, static current. */
public final class Characterize {
    private Characterize() { }

    public record Margins(double vOH, double vOM, double vOL, double vIL1, double vIH1, double vIL2, double vIH2,
                          double nmL, double nmML, double nmMH, double nmH) { }

    public record Arc(Cell cell, int pin, Logic3 from, Logic3 to, Logic3 outFrom, Logic3 outTo, int fanout,
                      double tpd, double tSettle, double midDwell) { }

    static final Logic3[] L = {Logic3.N, Logic3.O, Logic3.P};

    /** Static noise margins of the STI from its DC transfer curve (unity-gain points). */
    public static Margins margins(DeviceParams p, String tag) throws Exception {
        String deck = "* vtc\n" + p.spiceParams() + (CellModels.model == CellModels.Model.MOS ? MosCellModels.models() : "") + CellModels.cell(Cell.STI)
            + String.format(Locale.ROOT, "vdd vdd 0 %g%nvmid vmid 0 %g%nvss vss 0 0%nvin a 0 dc 0%nx1 a y vdd vmid vss STI%n", p.vdd(), p.vmid())
            + String.format(Locale.ROOT, ".control%nset wr_singlescale%noption numdgt=9%ndc vin 0 %g %g%nwrdata vtc.txt v(y)%nquit%n.endc%n.end%n", p.vdd(), p.vdd() / 2000);
        Path dir = Ngspice.workRoot().resolve("vtc-" + tag);
        Ngspice.run(dir, deck, 120);
        double[][] w = Ngspice.read(dir.resolve("vtc.txt"), 1);
        int n = w.length;
        double vOH = w[0][1], vOL = w[n - 1][1], vOM = w[n / 2][1];
        List<Double> crossings = new ArrayList<>();
        boolean steep = false;
        for (int i = 1; i < n; i++) {
            double slope = (w[i][1] - w[i - 1][1]) / (w[i][0] - w[i - 1][0]);
            boolean st = slope < -1;
            if (st != steep) { crossings.add((w[i][0] + w[i - 1][0]) / 2); steep = st; }
        }
        if (crossings.size() != 4) throw new IllegalStateException("expected 4 unity-gain points, got " + crossings);
        double il1 = crossings.get(0), ih1 = crossings.get(1), il2 = crossings.get(2), ih2 = crossings.get(3);
        return new Margins(vOH, vOM, vOL, il1, ih1, il2, ih2, il1 - vOL, vOM - ih1, il2 - vOM, vOH - ih2);
    }

    /** One timing arc per (cell, pin, from->to) with the first side-input assignment that makes the output change. */
    static List<Arc> arcPlan(Cell c) {
        List<Arc> arcs = new ArrayList<>();
        int k = c.arity;
        for (int pin = 0; pin < k; pin++) for (Logic3 f : L) for (Logic3 t : L) {
            if (f == t) continue;
            int sides = (int) Math.pow(3, k - 1);
            for (int sc = 0; sc < sides; sc++) {
                int[] a = side(k, pin, sc);
                a[pin] = f.bal(); int of = c.level(a);
                a[pin] = t.bal(); int ot = c.level(a);
                if (of != ot) { arcs.add(new Arc(c, pin, f, t, Logic3.ofBal(of), Logic3.ofBal(ot), sc, 0, 0, 0)); break; }
            }
        }
        return arcs;
    }

    static int[] side(int k, int pin, int sc) {
        int[] a = new int[k];
        for (int i = 0; i < k; i++) { if (i == pin) continue; a[i] = sc % 3 - 1; sc /= 3; }
        return a;
    }

    /** Measures all arcs of one cell at one fan-out load (fanout x cin) in a single transient run. */
    public static List<Arc> measure(DeviceParams p, Cell c, int fanout, String tag) throws Exception {
        List<Arc> plan = arcPlan(c);
        StringBuilder b = new StringBuilder("* arcs ").append(c).append('\n').append(p.spiceParams()).append(CellModels.model == CellModels.Model.MOS ? MosCellModels.models() : "").append(CellModels.cell(c));
        b.append(String.format(Locale.ROOT, "vdd vdd 0 %g%nvmid vmid 0 %g%nvss vss 0 0%n", p.vdd(), p.vmid()));
        double t0 = 1e-9, tr = 20e-12, tend = 3e-9;
        List<String> probes = new ArrayList<>();
        for (int j = 0; j < plan.size(); j++) {
            Arc arc = plan.get(j);
            int[] a = side(c.arity, arc.pin(), arc.fanout());
            b.append("x").append(j);
            for (int i = 0; i < c.arity; i++) b.append(" i").append(j).append("_").append(i);
            b.append(" o").append(j).append(" vdd vmid vss ").append(c.name()).append('\n');
            for (int i = 0; i < c.arity; i++) {
                if (i == arc.pin()) b.append(String.format(Locale.ROOT, "vs%d_%d i%d_%d 0 dc 0 pwl(0 %g %g %g %g %g)%n", j, i, j, i,
                    SpiceNetlist.volts(p, arc.from()), t0, SpiceNetlist.volts(p, arc.from()), t0 + tr, SpiceNetlist.volts(p, arc.to())));
                else b.append(String.format(Locale.ROOT, "vs%d_%d i%d_%d 0 dc %g%n", j, i, j, i, SpiceNetlist.volts(p, Logic3.ofBal(a[i]))));
            }
            b.append(String.format(Locale.ROOT, "cl%d o%d vss %g%n", j, j, fanout * p.cIn()));
            probes.add("v(i" + j + "_" + arc.pin() + ")");
            probes.add("v(o" + j + ")");
        }
        b.append(String.format(Locale.ROOT, ".control%nset wr_singlescale%noption numdgt=9%ntran 0.5p %g%nwrdata arcs.txt %s%nquit%n.endc%n.end%n", tend, String.join(" ", probes)));
        Path dir = Ngspice.workRoot().resolve("arcs-" + tag + "-" + c + "-fo" + fanout);
        Ngspice.run(dir, b.toString(), 300);
        double[][] w = Ngspice.read(dir.resolve("arcs.txt"), probes.size());
        List<Arc> out = new ArrayList<>();
        for (int j = 0; j < plan.size(); j++) {
            Arc arc = plan.get(j);
            // threshold-to-threshold timing: a ternary swing N<->P passes through O, so the 50% point is not a valid
            // reference; use the first decision threshold the signal crosses when leaving its starting level
            double vf = SpiceNetlist.volts(p, arc.outFrom()), vt = SpiceNetlist.volts(p, arc.outTo());
            double band = 0.1 * Math.abs(vt - vf);
            double tin = cross(w, 1 + 2 * j, firstThreshold(p, arc.from(), arc.to()), t0 - 1e-12);
            double tout = cross(w, 2 + 2 * j, firstThreshold(p, arc.outFrom(), arc.outTo()), t0 - 1e-12);
            double settle = Double.NaN, dwell = 0;
            for (int r = w.length - 1; r >= 0; r--) { if (Math.abs(w[r][2 + 2 * j] - vt) > band) { settle = (r + 1 < w.length ? w[r + 1][0] : w[r][0]) - tin; break; } }
            // time spent in the band of a level that is neither the start nor the end level (pass-through or glitch)
            for (int r = 1; r < w.length; r++) {
                if (w[r][0] <= t0) continue;
                Logic3 q = SpiceNetlist.quantize(p, w[r][2 + 2 * j]);
                if (q != arc.outFrom() && q != arc.outTo()) dwell += w[r][0] - w[r - 1][0];
            }
            out.add(new Arc(c, arc.pin(), arc.from(), arc.to(), arc.outFrom(), arc.outTo(), fanout, tout - tin, settle, dwell));
        }
        return out;
    }

    static double firstThreshold(DeviceParams p, Logic3 from, Logic3 to) {
        if (from == Logic3.N) return p.vthL();
        if (from == Logic3.P) return p.vthH();
        return to == Logic3.P ? p.vthH() : p.vthL();
    }

    private static double cross(double[][] w, int col, double v, double after) {
        for (int r = 1; r < w.length; r++) {
            if (w[r][0] < after) continue;
            double a = w[r - 1][col], b = w[r][col];
            if ((a - v) * (b - v) <= 0 && a != b) return w[r - 1][0] + (v - a) / (b - a) * (w[r][0] - w[r - 1][0]);
        }
        return Double.NaN;
    }

    /** Static rail currents per output level of the STI (input held at each level). */
    public static double[][] staticCurrents(DeviceParams p, String tag) throws Exception {
        double[][] res = new double[3][2];
        for (int l = 0; l < 3; l++) {
            String deck = "* static\n" + p.spiceParams() + (CellModels.model == CellModels.Model.MOS ? MosCellModels.models() : "") + CellModels.cell(Cell.STI)
                + String.format(Locale.ROOT, "vdd vdd 0 %g%nvmid vmid 0 %g%nvss vss 0 0%nvin a 0 dc %g%nx1 a y vdd vmid vss STI%n", p.vdd(), p.vmid(), SpiceNetlist.volts(p, L[l]))
                + ".control\nop\nprint i(vdd) i(vmid)\nquit\n.endc\n.end\n";
            String out = Ngspice.run(Ngspice.workRoot().resolve("static-" + tag + "-" + l), deck, 60);
            res[l][0] = num(out, "i\\(vdd\\)");
            res[l][1] = num(out, "i\\(vmid\\)");
        }
        return res;
    }

    private static double num(String out, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(key + " = ([-0-9.eE+]+)").matcher(out);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    /** Full characterization written as JSON. */
    public static void library(DeviceParams p, Path json, String tag, int threads) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        Map<String, Future<List<Arc>>> fs = new LinkedHashMap<>();
        for (Cell c : Cell.values()) {
            if (c == Cell.DFF || c.isTie()) continue;
            for (int fo : new int[]{1, 2, 4, 8}) fs.put(c + "/" + fo, ex.submit(() -> measure(p, c, fo, tag)));
        }
        Margins m = margins(p, tag);
        double[][] st = staticCurrents(p, tag);
        StringBuilder j = new StringBuilder("{\n");
        j.append(String.format(Locale.ROOT, "  \"device\": {\"vdd\": %g, \"vthL\": %g, \"vthH\": %g, \"s\": %g, \"rN\": %g, \"rM\": %g, \"rP\": %g, \"rInt\": %g, \"cInt\": %g, \"cIn\": %g, \"cOut\": %g, \"iMid\": %g},%n",
            p.vdd(), p.vthL(), p.vthH(), p.s(), p.rN(), p.rM(), p.rP(), p.rInt(), p.cInt(), p.cIn(), p.cOut(), p.iMid()));
        j.append(String.format(Locale.ROOT, "  \"noiseMargins\": {\"nmL\": %.4f, \"nmML\": %.4f, \"nmMH\": %.4f, \"nmH\": %.4f, \"vIL1\": %.4f, \"vIH1\": %.4f, \"vIL2\": %.4f, \"vIH2\": %.4f},%n",
            m.nmL(), m.nmML(), m.nmMH(), m.nmH(), m.vIL1(), m.vIH1(), m.vIL2(), m.vIH2()));
        j.append(String.format(Locale.ROOT, "  \"staticSTI\": {\"outP_iVdd\": %g, \"outO_iVdd\": %g, \"outO_iVmid\": %g, \"outN_iVdd\": %g},%n", st[0][0], st[1][0], st[1][1], st[2][0]));
        j.append("  \"arcs\": [\n");
        List<String> rows = new ArrayList<>();
        for (var e : fs.entrySet()) for (Arc a : e.getValue().get())
            rows.add(String.format(Locale.ROOT, "    {\"cell\": \"%s\", \"pin\": %d, \"in\": \"%s%s\", \"out\": \"%s%s\", \"fanout\": %d, \"tpd_ps\": %.2f, \"tsettle_ps\": %.2f, \"otherBandDwell_ps\": %.2f}",
                a.cell(), a.pin(), a.from().sym, a.to().sym, a.outFrom().sym, a.outTo().sym, a.fanout(), a.tpd() * 1e12, a.tSettle() * 1e12, a.midDwell() * 1e12));
        j.append(String.join(",\n", rows)).append("\n  ]\n}\n");
        ex.shutdown();
        Files.createDirectories(json.getParent());
        Files.writeString(json, j.toString());
    }

    public static void main(String[] a) throws Exception {
        library(DeviceParams.nominal(), Path.of(a.length > 0 ? a[0] : "cells/gt-param-nominal.json"), "nominal", 24);
    }
}
