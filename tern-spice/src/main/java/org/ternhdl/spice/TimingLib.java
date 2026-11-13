package org.ternhdl.spice;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.ternhdl.map.Cell;

/** Characterized timing (from Characterize JSON): delay per (cell, pin, input transition) versus fan-out. */
public final class TimingLib {
    private final Map<String, TreeMap<Integer, Double>> tpd = new HashMap<>();
    private final Map<String, TreeMap<Integer, Double>> dwell = new HashMap<>();
    private final Map<String, TreeMap<Integer, List<Double>>> cellAll = new HashMap<>();
    public final double meanPassDwell;

    public TimingLib(Path json) throws Exception {
        String s = Files.readString(json);
        Matcher m = Pattern.compile("\\{\"cell\": \"(\\w+)\", \"pin\": (\\d+), \"in\": \"(..)\", \"out\": \"(..)\", \"fanout\": (\\d+), \"tpd_ps\": ([-0-9.]+), \"tsettle_ps\": ([-0-9.NaN]+), \"otherBandDwell_ps\": ([-0-9.]+)\\}").matcher(s);
        double dsum = 0; int dn = 0;
        while (m.find()) {
            String key = m.group(1) + "/" + m.group(2) + "/" + m.group(3);
            int fo = Integer.parseInt(m.group(5));
            double d = Double.parseDouble(m.group(6)) * 1e-12, dw = Double.parseDouble(m.group(8)) * 1e-12;
            tpd.computeIfAbsent(key, k -> new TreeMap<>()).put(fo, d);
            dwell.computeIfAbsent(key, k -> new TreeMap<>()).put(fo, dw);
            cellAll.computeIfAbsent(m.group(1), k -> new TreeMap<>()).computeIfAbsent(fo, k -> new ArrayList<>()).add(d);
            String out = m.group(4);
            if ((out.equals("-+") || out.equals("+-")) && dw > 0) { dsum += dw; dn++; }
        }
        if (tpd.isEmpty()) throw new IllegalStateException("no arcs in " + json);
        meanPassDwell = dn > 0 ? dsum / dn : 15e-12;
    }

    private static double interp(TreeMap<Integer, Double> t, double fo) {
        Map.Entry<Integer, Double> lo = t.floorEntry((int) Math.floor(fo)), hi = t.ceilingEntry((int) Math.ceil(fo));
        if (lo == null) lo = t.firstEntry();
        if (hi == null) { // extrapolate from the last two points
            Map.Entry<Integer, Double> a = t.lowerEntry(t.lastKey()), b = t.lastEntry();
            return b.getValue() + (b.getValue() - a.getValue()) / (b.getKey() - a.getKey()) * (fo - b.getKey());
        }
        if (lo.getKey().equals(hi.getKey())) return lo.getValue();
        return lo.getValue() + (hi.getValue() - lo.getValue()) * (fo - lo.getKey()) / (hi.getKey() - lo.getKey());
    }

    private static char sym(int bal) { return bal < 0 ? '-' : bal == 0 ? '0' : '+'; }

    /** Delay of cell output after input pin changes from a to b (balanced values) with the given fan-out. */
    public double delay(Cell c, int pin, int a, int b, double fanout) {
        TreeMap<Integer, Double> t = tpd.get(c + "/" + pin + "/" + sym(a) + sym(b));
        if (t != null) return interp(t, Math.max(1, fanout));
        return worst(c, fanout);
    }

    /** Worst measured delay of a cell at the given fan-out (for static timing). */
    public double worst(Cell c, double fanout) {
        TreeMap<Integer, List<Double>> all = cellAll.get(c.name());
        TreeMap<Integer, Double> w = new TreeMap<>();
        for (var e : all.entrySet()) w.put(e.getKey(), Collections.max(e.getValue()));
        return interp(w, Math.max(1, fanout));
    }
}
