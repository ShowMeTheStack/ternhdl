package org.ternhdl.spice;

import java.util.*;
import org.ternhdl.core.Logic3;
import org.ternhdl.map.*;

/** Emits a combinational GT netlist as an ngspice deck driven by a sequence of input vectors. */
public final class SpiceNetlist {
    private SpiceNetlist() { }

    public static double volts(DeviceParams p, Logic3 l) { return l == Logic3.N ? 0 : l == Logic3.O ? p.vmid() : p.vdd(); }

    public static Logic3 quantize(DeviceParams p, double v) { return v < p.vthL() ? Logic3.N : v > p.vthH() ? Logic3.P : Logic3.O; }

    /**
     * @param vectors per period: input port -> little-endian digits (Bit ports use O/P)
     * @param period seconds per vector; transitions take tr seconds at the start of each period
     */
    public static String deck(Netlist nl, DeviceParams p, List<Map<String, Logic3[]>> vectors, double period, double tr, double step, List<String> probes) {
        return deck(nl, p, vectors, period, tr, step, probes, Map.of());
    }

    /** As above, with extra (wire) capacitance per net in farads. */
    public static String deck(Netlist nl, DeviceParams p, List<Map<String, Logic3[]>> vectors, double period, double tr, double step, List<String> probes, Map<Integer, Double> wireCap) {
        StringBuilder b = new StringBuilder("* TernHDL SPICE deck: ").append(nl.name).append('\n');
        b.append(p.spiceParams());
        b.append(CellModels.library());
        b.append(String.format(Locale.ROOT, "vdd vdd 0 %g%nvmid vmid 0 %g%nvss vss 0 0%n", p.vdd(), p.vmid()));
        for (var en : nl.inputs().entrySet()) {
            int[] nets = en.getValue();
            for (int i = 0; i < nets.length; i++) {
                StringBuilder pwl = new StringBuilder();
                double prev = -1;
                for (int k = 0; k < vectors.size(); k++) {
                    double v = volts(p, vectors.get(k).get(en.getKey())[i]);
                    double t0 = k * period;
                    if (k == 0) pwl.append(String.format(Locale.ROOT, "0 %g ", v));
                    else if (v != prev) pwl.append(String.format(Locale.ROOT, "%g %g %g %g ", t0, prev, t0 + tr, v));
                    prev = v;
                }
                b.append("v_in").append(nets[i]).append(" n").append(nets[i]).append(" 0 dc 0 pwl(").append(pwl.toString().trim()).append(")\n");
            }
        }
        int k = 0;
        for (Netlist.Inst i : nl.insts()) {
            if (i.cell == Cell.DFF) throw new IllegalArgumentException("combinational netlists only");
            if (i.cell.isTie()) {
                String rail = i.cell == Cell.TIE_N ? "vss" : i.cell == Cell.TIE_O ? "vmid" : "vdd";
                b.append("rtie").append(i.out).append(" n").append(i.out).append(" ").append(rail).append(" 1\n");
                continue;
            }
            b.append("x").append(k++).append(' ');
            for (int x : i.in) b.append('n').append(x).append(' ');
            b.append('n').append(i.out).append(" vdd vmid vss ").append(i.cell.name()).append('\n');
        }
        for (var wc : wireCap.entrySet()) b.append(String.format(Locale.ROOT, "cw%d n%d 0 %g%n", wc.getKey(), wc.getKey(), wc.getValue()));
        b.append(String.format(Locale.ROOT, ".control%nset wr_singlescale%noption numdgt=7%ntran %g %g%nwrdata out.txt", step, period * vectors.size()));
        for (String pr : probes) b.append(' ').append(pr);
        b.append("\nwrdata supply.txt i(vdd) i(vmid)\nquit\n.endc\n.end\n");
        return b.toString();
    }
}
