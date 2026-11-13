package org.ternhdl.spice;

import java.util.*;
import org.ternhdl.map.Cell;

/**
 * Transistor-level GT cells from generic level-1 MOSFETs (second device model): every input is decoded
 * by two skewed-threshold inverters (+ restoring inverters) into one-hot level lines; the output is connected to VSS by
 * an NMOS network for the input combinations whose output is N, to VDD by a PMOS network for P, and to VMID by an
 * NMOS+PMOS transmission network for O. The mid level comes from a rail, so no static current flows at O.
 * Generic models (not a foundry PDK): VDD 0.9 V, thresholds 0.15 / 0.30 V.
 */
public final class MosCellModels {
    private MosCellModels() { }

    public static String models() {
        return ".model nlo nmos level=1 vto=0.15 kp=200u lambda=0.05\n.model nhi nmos level=1 vto=0.30 kp=200u lambda=0.05\n"
             + ".model plo pmos level=1 vto=-0.15 kp=100u lambda=0.05\n.model phi pmos level=1 vto=-0.30 kp=100u lambda=0.05\n";
    }

    public static String subckt(Cell c) {
        int k = c.arity;
        StringBuilder b = new StringBuilder();
        b.append(".subckt ").append(c.name()).append(' ').append(CellModels.pins(k)).append("y vdd vmid vss\n");
        // level-1 models carry no device capacitance: add explicit input/output capacitance (same values as the
        // parametric cells) so transients have physical state
        for (int i = 0; i < k; i++) b.append("cin").append(i).append(" a").append(i).append(" vss 0.5f\n");
        b.append("cout y vss 1f\n");
        int[] count = {0};
        java.util.function.Supplier<String> id = () -> "m" + (count[0]++);
        for (int i = 0; i < k; i++) {
            String a = "a" + i;
            // lb_i = 1 when a < VthL ; hb_i = 1 when a < VthH ; l_i, h_i are their complements
            b.append(id.get()).append(" lb").append(i).append(' ').append(a).append(" vdd vdd phi w=0.2u l=1u\n");
            b.append(id.get()).append(" lb").append(i).append(' ').append(a).append(" vss vss nlo w=4u l=0.1u\n");
            b.append(id.get()).append(" hb").append(i).append(' ').append(a).append(" vdd vdd plo w=4u l=0.1u\n");
            b.append(id.get()).append(" hb").append(i).append(' ').append(a).append(" vss vss nhi w=0.2u l=1u\n");
            b.append(id.get()).append(" l").append(i).append(" lb").append(i).append(" vdd vdd phi w=1u l=0.1u\n");
            b.append(id.get()).append(" l").append(i).append(" lb").append(i).append(" vss vss nhi w=0.5u l=0.1u\n");
            b.append(id.get()).append(" h").append(i).append(" hb").append(i).append(" vdd vdd phi w=1u l=0.1u\n");
            b.append(id.get()).append(" h").append(i).append(" hb").append(i).append(" vss vss nhi w=0.5u l=0.1u\n");
        }
        int total = (int) Math.pow(3, k);
        int node = 0;
        for (int combo = 0; combo < total; combo++) {
            int[] bal = new int[k];
            int m = combo;
            for (int i = k - 1; i >= 0; i--) { bal[i] = m % 3 - 1; m /= 3; }
            int out = c.level(bal);
            // NMOS chain gates (active high) and PMOS chain gates (active low) that select this combination
            List<String> ng = new ArrayList<>(), pg = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                if (bal[i] == -1) { ng.add("lb" + i); pg.add("l" + i); }
                else if (bal[i] == 1) { ng.add("h" + i); pg.add("hb" + i); }
                else { ng.add("l" + i); ng.add("hb" + i); pg.add("lb" + i); pg.add("h" + i); }
            }
            if (out == -1 || out == 0) node = chain(b, id, "y", out == -1 ? "vss" : "vmid", ng, "nlo", "vss", "2u", node);
            if (out == 1 || out == 0) node = chain(b, id, "y", out == 1 ? "vdd" : "vmid", pg, "plo", "vdd", "4u", node);
        }
        b.append(".ends\n");
        return b.toString();
    }

    private static int chain(StringBuilder b, java.util.function.Supplier<String> id, String from, String to, List<String> gates, String model, String bulk, String w, int node) {
        String prev = from;
        for (int g = 0; g < gates.size(); g++) {
            String next = g + 1 == gates.size() ? to : "x" + (node++);
            b.append(id.get()).append(' ').append(prev).append(' ').append(gates.get(g)).append(' ').append(next).append(' ').append(bulk).append(' ').append(model).append(" w=").append(w).append(" l=0.1u\n");
            prev = next;
        }
        return node;
    }

    public static String library() {
        StringBuilder b = new StringBuilder(models());
        for (Cell c : Cell.values()) if (c != Cell.DFF && !c.isTie()) b.append(subckt(c));
        return b.toString();
    }

    public static int transistors(Cell c) {
        int n = 0;
        for (String l : subckt(c).split("\n")) if (l.startsWith("m")) n++;
        return n;
    }
}
