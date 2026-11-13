package org.ternhdl.encode;

import java.util.*;
import org.ternhdl.core.*;

/**
 * Emits the binary-encoded twin of a design as SystemVerilog. A trit vector of width w becomes logic [2w-1:0] with
 * digit i at bits [2i+1:2i]; a Bit stays one bit. Primitive digit operations are SV functions generated from the
 * same level tables as the simulators, under the chosen encoding. Registers use always_ff on the binary clk;
 * synchronous reset is already part of the IR (Design.finish()).
 */
public final class SvEmitter {
    /** Adder architecture for ADD nodes: RIPPLE (digit chain) or PREFIX (Sklansky over carry-transfer functions). */
    public enum AdderStyle { RIPPLE, PREFIX }

    public record Options(Encoding enc, boolean checked, boolean exposeAll, AdderStyle adders) {
        public Options(Encoding enc, boolean checked, boolean exposeAll) { this(enc, checked, exposeAll, AdderStyle.RIPPLE); }
        public static Options of(Encoding e) { return new Options(e, false, false); }
    }

    private final Design d;
    private final Options o;
    private final Encoding e;
    private final StringBuilder body = new StringBuilder();
    private final TreeMap<String, String> funcs = new TreeMap<>();
    private final boolean[] live;

    private SvEmitter(Design d, Options o) {
        d.finish();
        this.d = d; this.o = o; this.e = o.enc();
        this.live = liveness();
    }

    public static String emit(Design d, Options o) { return new SvEmitter(d, o).run(); }

    /** Name of the debug output port carrying node id (exposeAll mode). */
    public static String dbg(int id) { return "dbg_" + id; }

    // ---------------------------------------------------------------- liveness
    private boolean[] liveness() {
        int n = d.nodes().size();
        boolean[] lv = new boolean[n];
        Deque<Integer> work = new ArrayDeque<>();
        if (o.exposeAll()) for (int i = 0; i < n; i++) work.add(i);
        work.addAll(d.outputs().values());
        for (Design.Prop pr : d.props()) work.add(pr.node());
        if (!d.props().isEmpty() && d.resetNode() >= 0) work.add(d.resetNode());
        while (!work.isEmpty()) {
            int id = work.pop();
            if (lv[id]) continue;
            lv[id] = true;
            Node nd = d.node(id);
            for (int a : nd.args()) work.add(a);
            if (nd.op() == Op.REG) work.add(d.regNext(nd.aux()));
            if (nd.op() == Op.MEMRD) for (int[] w : d.mems().get(nd.aux()).writes()) for (int x : w) work.add(x);
        }
        return lv;
    }

    // ---------------------------------------------------------------- helpers
    private String code(Logic3 v) { return "2'b" + e.bits(v); }
    private String oCode() { return code(Logic3.O); }
    private static String sig(int id) { return "t_n" + id; }
    private static String dig(int id, int i) { return "t_n" + id + "[" + (2 * i + 1) + ":" + (2 * i) + "]"; }
    private static String range(Type t) { return t.isBit() ? "" : "[" + (2 * t.width() - 1) + ":0] "; }
    private void line(String s) { body.append("  ").append(s).append('\n'); }

    private String vecConst(Logic3[] le) {
        StringBuilder sb = new StringBuilder();
        sb.append(2 * le.length).append("'b");
        for (int i = le.length - 1; i >= 0; i--) sb.append(e.bits(le[i].isLevel() ? le[i] : Logic3.O));
        return sb.toString();
    }

    // ---------------------------------------------------------------- functions from tables
    private String fn(Prim p) {
        String name = "t_" + p.name().toLowerCase();
        if (funcs.containsKey(name)) return name;
        StringBuilder f = new StringBuilder();
        int ar = p.arity;
        f.append("  function automatic logic [1:0] ").append(name).append("(");
        for (int k = 0; k < ar; k++) f.append(k > 0 ? ", " : "").append("input logic [1:0] t_a").append(k);
        f.append(");\n    case (");
        if (ar == 1) f.append("t_a0"); else { f.append("{"); for (int k = 0; k < ar; k++) f.append(k > 0 ? ", " : "").append("t_a").append(k); f.append("}"); }
        f.append(")\n");
        int total = (int) Math.pow(3, ar);
        for (int c = 0; c < total; c++) {
            int[] bal = new int[ar];
            StringBuilder key = new StringBuilder();
            int m = c;
            for (int k = ar - 1; k >= 0; k--) { bal[k] = m % 3 - 1; m /= 3; }
            for (int k = 0; k < ar; k++) key.append(e.bits(Logic3.ofBal(bal[k])));
            f.append("      ").append(2 * ar).append("'b").append(key).append(": ").append(name).append(" = ").append(code(Logic3.ofBal(p.level(bal)))).append(";\n");
        }
        f.append("      default: ").append(name).append(" = ").append(oCode()).append(";\n    endcase\n  endfunction\n");
        funcs.put(name, f.toString());
        return name;
    }

    /** Full adder digit: returns {carry, sum}; or sum only when sumOnly. */
    private String fa(boolean sumOnly) {
        String name = sumOnly ? "t_fs" : "t_fa";
        if (funcs.containsKey(name)) return name;
        StringBuilder f = new StringBuilder();
        f.append("  function automatic logic [").append(sumOnly ? 1 : 3).append(":0] ").append(name)
         .append("(input logic [1:0] t_a, input logic [1:0] t_b, input logic [1:0] t_c);\n    case ({t_a, t_b, t_c})\n");
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) for (int c = -1; c <= 1; c++) {
            int s = a + b + c;
            int carry = s >= 2 ? 1 : s <= -2 ? -1 : 0;
            int r = s - 3 * carry;
            String key = e.bits(Logic3.ofBal(a)) + e.bits(Logic3.ofBal(b)) + e.bits(Logic3.ofBal(c));
            String val = sumOnly ? code(Logic3.ofBal(r)) : "{" + code(Logic3.ofBal(carry)) + ", " + code(Logic3.ofBal(r)) + "}";
            f.append("      6'b").append(key).append(": ").append(name).append(" = ").append(val).append(";\n");
        }
        f.append("      default: ").append(name).append(" = ").append(sumOnly ? oCode() : "{" + oCode() + ", " + oCode() + "}").append(";\n    endcase\n  endfunction\n");
        funcs.put(name, f.toString());
        return name;
    }

    private String cmp1() {
        String name = "t_cmp1";
        if (funcs.containsKey(name)) return name;
        StringBuilder f = new StringBuilder("  function automatic logic [1:0] t_cmp1(input logic [1:0] t_a, input logic [1:0] t_b);\n    case ({t_a, t_b})\n");
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++)
            f.append("      4'b").append(e.bits(Logic3.ofBal(a))).append(e.bits(Logic3.ofBal(b))).append(": t_cmp1 = ").append(code(Logic3.ofBal(Integer.signum(a - b)))).append(";\n");
        f.append("      default: t_cmp1 = ").append(oCode()).append(";\n    endcase\n  endfunction\n");
        funcs.put(name, f.toString());
        return name;
    }

    // ---------------------------------------------------------------- emission
    private String run() {
        for (String p : d.inputs().keySet()) reserved(p);
        for (String p : d.outputs().keySet()) reserved(p);
        List<String> ports = new ArrayList<>();
        boolean anyReg = false;
        for (Node nd : d.nodes()) if (live[nd.id()] && (nd.op() == Op.REG || nd.op() == Op.MEMRD)) anyReg = true;
        if (anyReg) ports.add("input logic clk");
        for (var en : d.inputs().entrySet()) ports.add("input logic " + range(d.node(en.getValue()).type()) + en.getKey());
        for (var en : d.outputs().entrySet()) ports.add("output logic " + range(d.node(en.getValue()).type()) + en.getKey());
        if (o.exposeAll()) for (Node nd : d.nodes()) ports.add("output logic " + range(nd.type()) + dbg(nd.id()));

        // internal nodes may be consumed only partly (e.g. a slice of a shifted address); that is benign, so only
        // UNUSEDSIGNAL is waived, and only for internal node declarations (ports stay fully linted)
        body.append("  /* verilator lint_off UNUSEDSIGNAL */\n");
        for (Node nd : d.nodes()) if (live[nd.id()] || nd.op() == Op.INPUT) line("logic " + range(nd.type()) + sig(nd.id()) + ";");
        body.append("  /* verilator lint_on UNUSEDSIGNAL */\n");
        for (var en : d.inputs().entrySet()) if (live[en.getValue()]) line("assign " + sig(en.getValue()) + " = " + en.getKey() + ";");
        for (Node nd : d.nodes()) if (live[nd.id()]) node(nd);
        for (int i = 0; i < d.regs().size(); i++) {
            var r = d.regs().get(i);
            if (!live[r.readNode()]) continue;
            line("logic " + range(r.type()) + "t_r" + i + ";");
            line("always_ff @(posedge clk) t_r" + i + " <= " + sig(d.regNext(i)) + ";");
        }
        memories();
        for (var en : d.outputs().entrySet()) line("assign " + en.getKey() + " = " + sig(en.getValue()) + ";");
        if (o.exposeAll()) for (Node nd : d.nodes()) line("assign " + dbg(nd.id()) + " = " + sig(nd.id()) + ";");
        if (o.checked()) checks();
        if (!d.props().isEmpty()) {
            body.append("  always_comb begin\n");
            for (Design.Prop pr : d.props())
                body.append("    ").append(pr.kind() == Design.PropKind.ASSERT ? "assert" : "assume").append(" (").append(d.resetNode() >= 0 ? sig(d.resetNode()) + " || " : "").append(sig(pr.node())).append(");   // ").append(pr.name()).append("\n");
            body.append("  end\n");
        }

        StringBuilder sv = new StringBuilder();
        sv.append("// Generated by TernHDL SvEmitter. Encoding ").append(e).append(" (O=").append(e.bits(Logic3.O))
          .append(", P=").append(e.bits(Logic3.P)).append(", N=").append(e.bits(Logic3.N)).append(").\n");
        sv.append("module ").append(d.name).append(" (\n");
        for (int i = 0; i < ports.size(); i++) sv.append("  ").append(ports.get(i)).append(i + 1 < ports.size() ? ",\n" : "\n");
        sv.append(");\n");
        for (String f : funcs.values()) sv.append(f);
        sv.append(body);
        sv.append("endmodule\n");
        return sv.toString();
    }

    /** Generated identifiers use the t_ prefix, debug ports dbg_, and the clock is clk. */
    private static void reserved(String port) {
        if (port.startsWith("t_") || port.startsWith("dbg_") || port.equals("clk"))
            throw new IllegalArgumentException("port name " + port + " is reserved (t_*, dbg_*, clk)");
    }

    private boolean memLive(Design.Mem mm) {
        for (Node nd : d.nodes()) if (live[nd.id()] && nd.op() == Op.MEMRD && nd.aux() == mm.index()) return true;
        return false;
    }

    /** Arrays with O-initialized content, index functions, and write ports (labelled so a clock enable can gate them). */
    private void memories() {
        for (Design.Mem mm : d.mems()) {
            if (!memLive(mm)) continue;
            int i = mm.index(), w = mm.type.width(), dep = mm.depth();
            line("logic [" + (2 * w - 1) + ":0] t_m" + i + " [0:" + (dep - 1) + "];");
            StringBuilder ini = new StringBuilder("initial begin for (int k = 0; k < " + dep + "; k++) t_m" + i + "[k] = " + vecConst(filled(w)) + ";");
            for (var en : mm.init().entrySet()) ini.append(" t_m").append(i).append("[").append(en.getKey()).append("] = ").append(vecConst(en.getValue())).append(";");
            line(ini.append(" end").toString());
            int ib = Math.max(1, 32 - Integer.numberOfLeadingZeros(dep - 1));
            StringBuilder f = new StringBuilder("  function automatic logic [" + (ib - 1) + ":0] t_ix" + i + "(input logic [" + (2 * mm.addrTrits - 1) + ":0] t_a);\n    t_ix" + i + " = " + ib + "'(0");
            int p3 = 1;
            for (int k = 0; k < mm.addrTrits; k++) { f.append(" + ").append(p3).append(" * t_u(t_a[").append(2 * k + 1).append(":").append(2 * k).append("])"); p3 *= 3; }
            f.append(");\n  endfunction\n");
            funcs.put("t_ix" + i, f.toString());
            unaryDigit();
            // write indices go through wires: Verilator 5.032 fails on function calls in NBA left-hand-side indices
            for (int k = 0; k < mm.writes().size(); k++) {
                line("logic [" + (ib - 1) + ":0] t_wi" + i + "_" + k + ";");
                line("assign t_wi" + i + "_" + k + " = t_ix" + i + "(" + sig(mm.writes().get(k)[1]) + ");");
            }
            StringBuilder wr = new StringBuilder("always_ff @(posedge clk) begin : t_mw" + i);
            for (int k = 0; k < mm.writes().size(); k++) { int[] wp = mm.writes().get(k); wr.append(" if (").append(sig(wp[0])).append(") t_m").append(i).append("[t_wi").append(i).append("_").append(k).append("] <= ").append(sig(wp[2])).append(";"); }
            line(wr.append(" end").toString());
        }
    }

    /** t_u: code -> unbalanced digit 0..2 (illegal code -> 0). */
    private void unaryDigit() {
        if (funcs.containsKey("t_u")) return;
        StringBuilder f = new StringBuilder("  function automatic int t_u(input logic [1:0] t_a);\n    case (t_a)\n");
        for (Logic3 l : new Logic3[]{Logic3.N, Logic3.O, Logic3.P}) f.append("      2'b").append(e.bits(l)).append(": t_u = ").append(l.bal() + 1).append(";\n");
        f.append("      default: t_u = 0;\n    endcase\n  endfunction\n");
        funcs.put("t_u", f.toString());
    }

    /** Carry out of a + b + c for one digit (code). */
    private String carryFn() {
        String name = "t_cy";
        if (funcs.containsKey(name)) return name;
        StringBuilder f = new StringBuilder("  function automatic logic [1:0] t_cy(input logic [1:0] t_a, input logic [1:0] t_b, input logic [1:0] t_c);\n    case ({t_a, t_b, t_c})\n");
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int c = -1; c <= 1; c++) {
            int sm = x + y + c, cy = sm >= 2 ? 1 : sm <= -2 ? -1 : 0;
            f.append("      6'b").append(e.bits(Logic3.ofBal(x))).append(e.bits(Logic3.ofBal(y))).append(e.bits(Logic3.ofBal(c))).append(": t_cy = ").append(code(Logic3.ofBal(cy))).append(";\n");
        }
        f.append("      default: t_cy = ").append(oCode()).append(";\n    endcase\n  endfunction\n");
        funcs.put(name, f.toString());
        return name;
    }

    /** 3:1 select on a digit code: sel N -> x, O -> y, P -> z. */
    private String selFn() {
        String name = "t_sel";
        if (funcs.containsKey(name)) return name;
        funcs.put(name, "  function automatic logic [1:0] t_sel(input logic [1:0] t_s, input logic [1:0] t_x, input logic [1:0] t_y, input logic [1:0] t_z);\n"
            + "    case (t_s)\n      2'b" + e.bits(Logic3.N) + ": t_sel = t_x;\n      2'b" + e.bits(Logic3.O) + ": t_sel = t_y;\n      2'b" + e.bits(Logic3.P) + ": t_sel = t_z;\n"
            + "      default: t_sel = " + oCode() + ";\n    endcase\n  endfunction\n");
        return name;
    }

    /**
     * Sklansky parallel-prefix balanced adder: digit i's carry-transfer table T_i[c] = carry(a_i + b_i + c) for
     * c = N, O, P; tables compose as (hi o lo)[c] = hi[lo[c]] (a MUX3 per entry); the carry into digit i is
     * (T_{i-1} o ... o T_0)[cin]; sum digit = sum(a_i, b_i, carry_i).
     */
    private void prefixAdd(int id, int[] a, int w, String cin) {
        String cy = carryFn(), sel = selFn(), fs = fa(true);
        body.append("  /* verilator lint_off UNUSEDSIGNAL */\n");   // the last digit's prefix tables are not consumed
        String[] cs = {code(Logic3.N), code(Logic3.O), code(Logic3.P)};
        String[][] t = new String[w][3];
        for (int i = 0; i < w; i++) for (int c = 0; c < 3; c++) {
            t[i][c] = "t_p" + id + "_0_" + i + "_" + c;
            line("logic [1:0] " + t[i][c] + ";");
            line("assign " + t[i][c] + " = " + cy + "(" + dig(a[0], i) + ", " + dig(a[1], i) + ", " + cs[c] + ");");
        }
        String[][] pre = t;
        int lvl = 0;
        for (int span = 1; span < w; span *= 2) {
            lvl++;
            String[][] nx = new String[w][];
            for (int i = 0; i < w; i++) {
                if ((i / span) % 2 == 1) {
                    int j = (i / span) * span - 1;
                    nx[i] = new String[3];
                    for (int c = 0; c < 3; c++) {
                        nx[i][c] = "t_p" + id + "_" + lvl + "_" + i + "_" + c;
                        line("logic [1:0] " + nx[i][c] + ";");
                        line("assign " + nx[i][c] + " = " + sel + "(" + pre[j][c] + ", " + pre[i][0] + ", " + pre[i][1] + ", " + pre[i][2] + ");");
                    }
                } else nx[i] = pre[i];
            }
            pre = nx;
        }
        for (int i = 0; i < w; i++) {
            String ci = i == 0 ? cin : sel + "(" + cin + ", " + pre[i - 1][0] + ", " + pre[i - 1][1] + ", " + pre[i - 1][2] + ")";
            line("assign " + dig(id, i) + " = " + fs + "(" + dig(a[0], i) + ", " + dig(a[1], i) + ", " + ci + ");");
        }
        body.append("  /* verilator lint_on UNUSEDSIGNAL */\n");
    }

    private void checks() {
        StringBuilder c = new StringBuilder();
        String ill = "2'b" + (e.illegal >> 1 & 1) + (e.illegal & 1);
        for (Node nd : d.nodes()) {
            if (!live[nd.id()] || nd.type().isBit()) continue;
            for (int i = 0; i < nd.type().width(); i++)
                c.append("    assert (").append(dig(nd.id(), i)).append(" != ").append(ill).append(");   // illegal trit code check: t_n")
                 .append(nd.id()).append(" digit ").append(i).append("\n");
        }
        if (c.length() > 0) body.append("  always_comb begin\n").append(c).append("  end\n");
    }

    private void node(Node nd) {
        int id = nd.id();
        int w = nd.type().width();
        int[] a = nd.args();
        switch (nd.op()) {
            case INPUT -> { }
            case CONST -> line("assign " + sig(id) + " = " + (nd.type().isBit() ? (nd.constant()[0] == Logic3.P ? "1'b1" : "1'b0") : vecConst(nd.constant())) + ";");
            case REG -> line("assign " + sig(id) + " = t_r" + nd.aux() + ";");
            case MEMRD -> line("assign " + sig(id) + " = t_m" + nd.aux() + "[t_ix" + nd.aux() + "(" + sig(a[0]) + ")];");
            case PRIM -> {
                if (nd.type().isBit()) {
                    String op = switch (nd.prim()) { case MIN -> " & "; case MAX -> " | "; default -> throw new IllegalStateException("bit prim " + nd.prim()); };
                    line("assign " + sig(id) + " = " + sig(a[0]) + op + sig(a[1]) + ";");
                } else {
                    String f = fn(nd.prim());
                    for (int i = 0; i < w; i++) {
                        StringBuilder call = new StringBuilder(f).append("(");
                        for (int k = 0; k < a.length; k++) call.append(k > 0 ? ", " : "").append(dig(a[k], i));
                        line("assign " + dig(id, i) + " = " + call + ");");
                    }
                }
            }
            case ADD -> {
                String cin = a.length == 3 ? dig(a[2], 0) : oCode();
                if (o.adders() == AdderStyle.PREFIX && w >= 4) { prefixAdd(id, a, w, cin); break; }
                String carry = cin;
                for (int i = 0; i < w; i++) {
                    if (i == w - 1) {
                        line("assign " + dig(id, i) + " = " + fa(true) + "(" + dig(a[0], i) + ", " + dig(a[1], i) + ", " + carry + ");");
                    } else {
                        String f = "t_f" + id + "_" + i;
                        line("logic [3:0] " + f + ";");
                        line("assign " + f + " = " + fa(false) + "(" + dig(a[0], i) + ", " + dig(a[1], i) + ", " + carry + ");");
                        line("assign " + dig(id, i) + " = " + f + "[1:0];");
                        carry = f + "[3:2]";
                    }
                }
            }
            case SHL -> { for (int i = 0; i < w; i++) line("assign " + dig(id, i) + " = " + (i - nd.aux() >= 0 ? dig(a[0], i - nd.aux()) : oCode()) + ";"); }
            case SHR -> { for (int i = 0; i < w; i++) line("assign " + dig(id, i) + " = " + (i + nd.aux() < w ? dig(a[0], i + nd.aux()) : oCode()) + ";"); }
            case SLICE -> {
                if (nd.type().isBit()) line("assign " + sig(id) + " = " + sig(a[0]) + ";");
                else for (int i = 0; i < w; i++) line("assign " + dig(id, i) + " = " + dig(a[0], nd.aux2() + i) + ";");
            }
            case CONCAT -> {
                StringBuilder s = new StringBuilder("{");
                for (int k = 0; k < a.length; k++) s.append(k > 0 ? ", " : "").append(sig(a[k]));
                line("assign " + sig(id) + " = " + s + "};");
            }
            case CMP -> {
                String f = cmp1();
                int aw = d.node(a[0]).type().width();
                String prev = null;
                for (int i = 0; i < aw; i++) {
                    String dd = "t_d" + id + "_" + i, k = "t_k" + id + "_" + i;
                    line("logic [1:0] " + dd + ";");
                    line("assign " + dd + " = " + f + "(" + dig(a[0], i) + ", " + dig(a[1], i) + ");");
                    line("logic [1:0] " + k + ";");
                    line("assign " + k + " = " + (prev == null ? dd : "(" + dd + " != " + oCode() + ") ? " + dd + " : " + prev) + ";");
                    prev = k;
                }
                line("assign " + sig(id) + " = " + prev + ";");
            }
            case TOBIT -> {
                Logic3 lv = nd.aux() == 0 ? Logic3.P : nd.aux() == 1 ? Logic3.N : Logic3.O;
                line("assign " + sig(id) + " = (" + sig(a[0]) + " == " + code(lv) + ");");
            }
            case TOTRIT -> line("assign " + sig(id) + " = " + sig(a[0]) + " ? " + code(Logic3.P) + " : " + code(nd.aux() == 0 ? Logic3.O : Logic3.N) + ";");
            case BNOT -> line("assign " + sig(id) + " = ~" + sig(a[0]) + ";");
            case MUXT -> {
                String fill = nd.type().isBit() ? "1'b0" : vecConst(filled(w));   // Bit-typed data: 1-bit fill
                line("assign " + sig(id) + " = (" + sig(a[0]) + " == " + code(Logic3.N) + ") ? " + sig(a[1]) + " : (" + sig(a[0]) + " == " + code(Logic3.O)
                     + ") ? " + sig(a[2]) + " : (" + sig(a[0]) + " == " + code(Logic3.P) + ") ? " + sig(a[3]) + " : " + fill + ";");
            }
            case MUXB -> line("assign " + sig(id) + " = " + sig(a[0]) + " ? " + sig(a[1]) + " : " + sig(a[2]) + ";");
            default -> throw new IllegalStateException("op " + nd.op());
        }
    }

    private static Logic3[] filled(int w) { Logic3[] f = new Logic3[w]; Arrays.fill(f, Logic3.O); return f; }
}
