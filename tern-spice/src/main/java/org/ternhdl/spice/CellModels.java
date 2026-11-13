package org.ternhdl.spice;

import org.ternhdl.map.Cell;

/** ngspice subcircuits for GT cells under the parametric behavioural device (generated from the cell truth tables). */
public final class CellModels {
    private CellModels() { }

    /** Device model used by decks: PARAMETRIC (behavioural) or MOS (transistor-level, MosCellModels). */
    public enum Model { PARAMETRIC, MOS }
    public static Model model = Model.PARAMETRIC;

    /** Subcircuit of a cell in the selected model. */
    public static String cell(Cell c) { return model == Model.MOS ? MosCellModels.subckt(c) : subckt(c); }

    static String pins(int n) { StringBuilder s = new StringBuilder(); for (int i = 0; i < n; i++) s.append("a").append(i).append(' '); return s.toString(); }

    private static String lvl(int bal) { return bal == -1 ? "v(vss)" : bal == 0 ? "v(vmid)" : "v(vdd)"; }

    private static String ind(String node, int bal) {
        String v = "v(" + node + ")";
        String sn = "(1/(1+exp((" + v + "-vthl)/s)))", sp = "(1/(1+exp(-(" + v + "-vthh)/s)))";
        return bal == -1 ? sn : bal == 1 ? sp : "(1-" + sn + "-" + sp + ")";
    }

    /** Subckt text for one cell. Pins: inputs a0.., output y, rails vdd vmid vss. */
    public static String subckt(Cell c) {
        int k = c.arity;
        StringBuilder b = new StringBuilder();
        b.append(".subckt ").append(c.name()).append(' ').append(pins(k)).append("y vdd vmid vss\n");
        for (int i = 0; i < k; i++) b.append("cin").append(i).append(" a").append(i).append(" vss {cin}\n");
        StringBuilder tgt = new StringBuilder();
        int total = (int) Math.pow(3, k);
        for (int combo = 0; combo < total; combo++) {
            int[] bal = new int[k];
            int m = combo;
            for (int i = k - 1; i >= 0; i--) { bal[i] = m % 3 - 1; m /= 3; }
            StringBuilder term = new StringBuilder();
            for (int i = 0; i < k; i++) term.append(i > 0 ? "*" : "").append(ind("a" + i, bal[i]));
            tgt.append(combo > 0 ? " + " : "").append(term).append("*").append(lvl(c.level(bal)));
        }
        b.append("btgt t vss v = ").append(tgt).append('\n');
        b.append("rint t ti {rint}\nci ti vss {cint}\n");
        // driver resistance by target level: weighted by the target's own level indicators
        b.append("bdrv y vss i = -(v(ti)-v(y)) / ({rn}*").append(ind("ti", -1)).append(" + {rm}*").append(ind("ti", 0))
         .append(" + {rp}*").append(ind("ti", 1)).append(")\n");
        b.append("co y vss {cout}\n");
        b.append("bstat vdd vss i = {imid}*").append(ind("y", 0)).append('\n');
        b.append(".ends\n");
        return b.toString();
    }

    public static String library() {
        if (model == Model.MOS) return MosCellModels.library();
        StringBuilder b = new StringBuilder();
        for (Cell c : Cell.values()) if (c != Cell.DFF && !c.isTie()) b.append(subckt(c));
        return b.toString();
    }
}
