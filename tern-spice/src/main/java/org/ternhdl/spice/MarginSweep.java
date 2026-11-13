package org.ternhdl.spice;

import java.util.*;
import java.util.concurrent.*;

/** Noise margins of the STI versus logistic steepness and supply (not a test). */
public final class MarginSweep {
    public static void main(String[] a) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(24);
        List<Future<String>> fs = new ArrayList<>();
        for (double vdd : new double[]{0.6, 0.9, 1.2}) for (double s : new double[]{0.005, 0.01, 0.02, 0.03, 0.04}) {
            fs.add(ex.submit(() -> {
                DeviceParams p = DeviceParams.nominal().withVdd(vdd).withS(s);
                try {
                    Characterize.Margins m = Characterize.margins(p, String.format(Locale.ROOT, "v%.1f-s%.3f", vdd, s));
                    double min = Math.min(Math.min(m.nmL(), m.nmML()), Math.min(m.nmMH(), m.nmH()));
                    return String.format(Locale.ROOT, "| %.1f | %.0f | %.3f | %.3f | %.3f | %.3f | %.3f | %.0f%% |", vdd, s * 1000, m.nmL(), m.nmML(), m.nmMH(), m.nmH(), vdd / 4, 100 * min / (vdd / 4));
                } catch (Exception e) { return String.format(Locale.ROOT, "| %.1f | %.0f | no 4 unity-gain points: %s |", vdd, s * 1000, e.getMessage()); }
            }));
        }
        System.out.println("| VDD (V) | s (mV) | NML | NMML | NMMH | NMH | ideal VDD/4 | min/ideal |\n|---|---|---|---|---|---|---|---|");
        for (Future<String> f : fs) System.out.println(f.get());
        ex.shutdown();
    }
}
