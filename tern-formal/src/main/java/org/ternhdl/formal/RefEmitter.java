package org.ternhdl.formal;

import java.math.BigInteger;
import java.util.*;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;

/**
 * Independent SystemVerilog reference model of a design, used only for formal equivalence against the twin.
 * Internally every digit is a signed 2-bit integer (-1, 0, +1). Primitives are arithmetic expressions written
 * directly from the spec; vector ADD and CMP use whole-vector integer values in 64-bit signed arithmetic with
 * explicit wrap and digit extraction by balanced division by 3. Encodings appear only at the ports, so the port
 * list and encoding are identical to SvEmitter's twin (same liveness rule, same dbg_ ports).
 */
public final class RefEmitter {
    /** DIGIT_SERIAL follows the spec's digit rules (default, scales); VALUE uses whole-vector integers (small widths). */
    public enum Arith { DIGIT_SERIAL, VALUE }

    private final Design d;
    private final Encoding e;
    private final boolean exposeAll;
    private final Arith arith;
    private final StringBuilder body = new StringBuilder();
    private final boolean[] live;

    private RefEmitter(Design d, Encoding e, boolean exposeAll, Arith arith) {
        d.finish();
        this.d = d; this.e = e; this.exposeAll = exposeAll; this.arith = arith;
        live = liveness();
    }

    public static String emit(Design d, Encoding e, String module, boolean exposeAll) { return emit(d, e, module, exposeAll, Arith.DIGIT_SERIAL); }
    public static String emit(Design d, Encoding e, String module, boolean exposeAll, Arith arith) { return new RefEmitter(d, e, exposeAll, arith).run(module); }

    private boolean[] liveness() {
        int n = d.nodes().size();
        boolean[] lv = new boolean[n];
        Deque<Integer> work = new ArrayDeque<>();
        if (exposeAll) for (int i = 0; i < n; i++) work.add(i);
        work.addAll(d.outputs().values());
        for (Design.Prop pr : d.props()) work.add(pr.node());
        while (!work.isEmpty()) {
            int id = work.pop();
            if (lv[id]) continue;
            lv[id] = true;
            Node nd = d.node(id);
            for (int a : nd.args()) work.add(a);
            if (nd.op() == Op.REG) work.add(d.regNext(nd.aux()));
        }
        return lv;
    }

    private static String v(int id) { return "t_v" + id; }
    private static String dw(int id, int i) { return "t_v" + id + "[" + (2 * i + 1) + ":" + (2 * i) + "]"; }
    private static String dr(int id, int i) { return "$signed(" + dw(id, i) + ")"; }
    private static String range(Type t) { return t.isBit() ? "" : "[" + (2 * t.width() - 1) + ":0] "; }
    private void line(String s) { body.append("  ").append(s).append('\n'); }
    private static String lit(int bal) { return bal == -1 ? "2'sb11" : bal == 0 ? "2'sb00" : "2'sb01"; }

    private String functions() {
        StringBuilder f = new StringBuilder();
        f.append("  function automatic logic signed [1:0] r_dec(input logic [1:0] c);\n    case (c)\n");
        for (Logic3 l : new Logic3[]{Logic3.N, Logic3.O, Logic3.P})
            f.append("      2'b").append(e.bits(l)).append(": r_dec = ").append(lit(l.bal())).append(";\n");
        f.append("      default: r_dec = 2'sb00;\n    endcase\n  endfunction\n");
        f.append("  function automatic logic [1:0] r_enc(input logic signed [1:0] x);\n    case (x)\n");
        for (Logic3 l : new Logic3[]{Logic3.N, Logic3.O, Logic3.P})
            f.append("      ").append(lit(l.bal())).append(": r_enc = 2'b").append(e.bits(l)).append(";\n");
        f.append("      default: r_enc = 2'b").append(e.illegal >> 1 & 1).append(e.illegal & 1).append(";   // invalid digit -> illegal code, never equal to a legal twin code\n    endcase\n  endfunction\n");
        f.append("""
          function automatic logic signed [1:0] r_sti(input logic signed [1:0] x); r_sti = -x; endfunction
          function automatic logic signed [1:0] r_pti(input logic signed [1:0] x); r_pti = (x == 2'sb01) ? 2'sb11 : 2'sb01; endfunction
          function automatic logic signed [1:0] r_nti(input logic signed [1:0] x); r_nti = (x == 2'sb11) ? 2'sb01 : 2'sb11; endfunction
          function automatic logic signed [1:0] r_ici(input logic signed [1:0] x); r_ici = (x == 2'sb01) ? 2'sb11 : x + 2'sb01; endfunction
          function automatic logic signed [1:0] r_dci(input logic signed [1:0] x); r_dci = (x == 2'sb11) ? 2'sb01 : x - 2'sb01; endfunction
          function automatic logic signed [1:0] r_iszero(input logic signed [1:0] x); r_iszero = (x == 2'sb00) ? 2'sb01 : 2'sb11; endfunction
          function automatic logic signed [1:0] r_min(input logic signed [1:0] a, input logic signed [1:0] b); r_min = (a < b) ? a : b; endfunction
          function automatic logic signed [1:0] r_max(input logic signed [1:0] a, input logic signed [1:0] b); r_max = (a > b) ? a : b; endfunction
          function automatic logic signed [1:0] r_cons(input logic signed [1:0] a, input logic signed [1:0] b); r_cons = (a == b) ? a : 2'sb00; endfunction
          function automatic logic signed [1:0] r_any(input logic signed [1:0] a, input logic signed [1:0] b);
            logic signed [2:0] s;
            s = a + b;
            if (s > 3'sb001) r_any = 2'sb01; else if (s < 3'sb111) r_any = 2'sb11; else r_any = s[1:0];
          endfunction
          function automatic logic signed [1:0] r_sum(input logic signed [1:0] a, input logic signed [1:0] b);
            logic signed [2:0] s;
            logic signed [2:0] t;
            s = a + b;
            if (s > 3'sb001) t = s - 3'sb011; else if (s < 3'sb111) t = s + 3'sb011; else t = s;
            r_sum = t[1:0];
          endfunction
        """.stripIndent().indent(2));
        return f.toString();
    }

    private String value64(int id) {
        int w = d.node(id).type().width();
        StringBuilder s = new StringBuilder("64'sd0");
        BigInteger p = BigInteger.ONE;
        for (int i = 0; i < w; i++) { s.append(" + ").append(dr(id, i)).append(" * 64'sd").append(p); p = p.multiply(BigInteger.valueOf(3)); }
        return s.toString();
    }

    private String run(String module) {
        if (arith == Arith.VALUE) for (Node nd : d.nodes()) if (nd.type().width() > 36 && (nd.op() == Op.ADD || nd.op() == Op.CMP))
            throw new IllegalArgumentException("reference model arithmetic limited to 36 trits");
        List<String> ports = new ArrayList<>();
        boolean anyReg = false;
        for (Node nd : d.nodes()) if (live[nd.id()] && nd.op() == Op.REG) anyReg = true;
        if (anyReg) ports.add("input logic clk");
        for (var en : d.inputs().entrySet()) ports.add("input logic " + range(d.node(en.getValue()).type()) + en.getKey());
        for (var en : d.outputs().entrySet()) ports.add("output logic " + range(d.node(en.getValue()).type()) + en.getKey());
        if (exposeAll) for (Node nd : d.nodes()) ports.add("output logic " + range(nd.type()) + SvEmitter.dbg(nd.id()));

        for (Node nd : d.nodes()) if (live[nd.id()]) line("logic " + range(nd.type()) + v(nd.id()) + ";");
        for (Node nd : d.nodes()) if (live[nd.id()]) node(nd);
        for (int i = 0; i < d.regs().size(); i++) {
            var r = d.regs().get(i);
            if (!live[r.readNode()]) continue;
            line("logic " + range(r.type()) + "t_r" + i + ";");
            line("always_ff @(posedge clk) t_r" + i + " <= " + v(d.regNext(i)) + ";");
        }
        for (var en : d.outputs().entrySet()) line("assign " + en.getKey() + " = " + enc(en.getValue()) + ";");
        if (exposeAll) for (Node nd : d.nodes()) line("assign " + SvEmitter.dbg(nd.id()) + " = " + enc(nd.id()) + ";");

        StringBuilder sv = new StringBuilder("// Generated by TernHDL RefEmitter (independent reference model). Ports encoded with " + e + ".\n");
        sv.append("module ").append(module).append(" (\n");
        for (int i = 0; i < ports.size(); i++) sv.append("  ").append(ports.get(i)).append(i + 1 < ports.size() ? ",\n" : "\n");
        sv.append(");\n").append(functions()).append(body).append("endmodule\n");
        return sv.toString();
    }

    /** Encoded value of node id as an expression. */
    private String enc(int id) {
        Type t = d.node(id).type();
        if (t.isBit()) return v(id);
        StringBuilder s = new StringBuilder("{");
        for (int i = t.width() - 1; i >= 0; i--) s.append("r_enc(").append(dr(id, i)).append(")").append(i > 0 ? ", " : "");
        return s.append("}").toString();
    }

    private void node(Node nd) {
        int id = nd.id();
        int w = nd.type().width();
        int[] a = nd.args();
        boolean bit = nd.type().isBit();
        switch (nd.op()) {
            case INPUT -> {
                String port = nd.name();
                if (bit) line("assign " + v(id) + " = " + port + ";");
                else for (int i = 0; i < w; i++) line("assign " + dw(id, i) + " = r_dec(" + port + "[" + (2 * i + 1) + ":" + (2 * i) + "]);");
            }
            case CONST -> {
                if (bit) line("assign " + v(id) + " = " + (nd.constant()[0] == Logic3.P ? "1'b1" : "1'b0") + ";");
                else for (int i = 0; i < w; i++) line("assign " + dw(id, i) + " = " + lit(nd.constant()[i].isLevel() ? nd.constant()[i].bal() : 0) + ";");
            }
            case REG -> line("assign " + v(id) + " = t_r" + nd.aux() + ";");
            case PRIM -> {
                if (bit) line("assign " + v(id) + " = " + v(a[0]) + (nd.prim() == Prim.MIN ? " & " : " | ") + v(a[1]) + ";");
                else for (int i = 0; i < w; i++) {
                    String f = "r_" + nd.prim().name().toLowerCase();
                    line("assign " + dw(id, i) + " = " + f + "(" + dr(a[0], i) + (a.length > 1 ? ", " + dr(a[1], i) : "") + ");");
                }
            }
            case ADD -> {
                if (arith == Arith.DIGIT_SERIAL) {
                    // spec section 5: per digit s = a + b + c in [-3, 3]; carry +1 if s >= 2, -1 if s <= -2; digit r = s - 3*carry
                    String c = a.length == 3 ? dr(a[2], 0) : "3'sb000";
                    for (int i = 0; i < w; i++) {
                        String sv = "t_s" + id + "_" + i, cv = "t_c" + id + "_" + (i + 1);
                        line("logic signed [2:0] " + sv + ";");
                        line("assign " + sv + " = " + dr(a[0], i) + " + " + dr(a[1], i) + " + " + c + ";");
                        String carry = "((" + sv + " >= 3'sb010) ? 3'sb001 : (" + sv + " <= 3'sb110) ? 3'sb111 : 3'sb000)";
                        String r = "t_d" + id + "_" + i;
                        line("logic signed [2:0] " + r + ";");
                        line("assign " + r + " = " + sv + " - 3'sd3 * " + carry + ";");
                        line("assign " + dw(id, i) + " = " + r + "[1:0];");
                        if (i + 1 < w) { line("logic signed [2:0] " + cv + ";"); line("assign " + cv + " = " + carry + ";"); c = cv; }
                    }
                } else {
                    // whole-vector integer sum, wrap by comparison, greedy balanced digit extraction from the MSB (no division)
                    BigInteger m = BigInteger.valueOf(3).pow(w), h = m.subtract(BigInteger.ONE).shiftRight(1);
                    line("logic signed [63:0] t_s" + id + ", t_w" + id + ";");
                    line("assign t_s" + id + " = " + value64(a[0]) + " + " + value64(a[1]) + (a.length == 3 ? " + " + dr(a[2], 0) : "") + ";");
                    line("assign t_w" + id + " = (t_s" + id + " > 64'sd" + h + ") ? t_s" + id + " - 64'sd" + m + " : (t_s" + id + " < -64'sd" + h + ") ? t_s" + id + " + 64'sd" + m + " : t_s" + id + ";");
                    String rem = "t_w" + id;
                    for (int i = w - 1; i >= 0; i--) {
                        BigInteger p3 = BigInteger.valueOf(3).pow(i), hi = p3.subtract(BigInteger.ONE).shiftRight(1);
                        line("assign " + dw(id, i) + " = (" + rem + " > 64'sd" + hi + ") ? 2'sb01 : (" + rem + " < -64'sd" + hi + ") ? 2'sb11 : 2'sb00;");
                        if (i > 0) {
                            String nr = "t_q" + id + "_" + i;
                            line("logic signed [63:0] " + nr + ";");
                            line("assign " + nr + " = " + rem + " - " + dr(id, i) + " * 64'sd" + p3 + ";");
                            rem = nr;
                        }
                    }
                }
            }
            case CMP -> {
                if (arith == Arith.DIGIT_SERIAL) {
                    // the most significant differing digit decides
                    int aw = d.node(a[0]).type().width();
                    String acc = "2'sb00";
                    for (int i = 0; i < aw; i++) {
                        String k = "t_k" + id + "_" + i;
                        line("logic signed [1:0] " + k + ";");
                        line("assign " + k + " = (" + dr(a[0], i) + " > " + dr(a[1], i) + ") ? 2'sb01 : (" + dr(a[0], i) + " < " + dr(a[1], i) + ") ? 2'sb11 : " + acc + ";");
                        acc = k;
                    }
                    line("assign " + dw(id, 0) + " = " + acc + ";");
                } else {
                    line("logic signed [63:0] t_ca" + id + ", t_cb" + id + ";");
                    line("assign t_ca" + id + " = " + value64(a[0]) + ";");
                    line("assign t_cb" + id + " = " + value64(a[1]) + ";");
                    line("assign " + dw(id, 0) + " = (t_ca" + id + " < t_cb" + id + ") ? 2'sb11 : (t_ca" + id + " > t_cb" + id + ") ? 2'sb01 : 2'sb00;");
                }
            }
            case SHL -> { for (int i = 0; i < w; i++) line("assign " + dw(id, i) + " = " + (i - nd.aux() >= 0 ? dw(a[0], i - nd.aux()) : "2'sb00") + ";"); }
            case SHR -> { for (int i = 0; i < w; i++) line("assign " + dw(id, i) + " = " + (i + nd.aux() < w ? dw(a[0], i + nd.aux()) : "2'sb00") + ";"); }
            case SLICE -> {
                if (bit) line("assign " + v(id) + " = " + v(a[0]) + ";");
                else for (int i = 0; i < w; i++) line("assign " + dw(id, i) + " = " + dw(a[0], nd.aux2() + i) + ";");
            }
            case CONCAT -> {
                StringBuilder s = new StringBuilder("{");
                for (int k = 0; k < a.length; k++) s.append(k > 0 ? ", " : "").append(v(a[k]));
                line("assign " + v(id) + " = " + s + "};");
            }
            case TOBIT -> {
                int want = nd.aux() == 0 ? 1 : nd.aux() == 1 ? -1 : 0;
                line("assign " + v(id) + " = (" + dr(a[0], 0) + " == " + lit(want) + ");");
            }
            case TOTRIT -> line("assign " + dw(id, 0) + " = " + v(a[0]) + " ? 2'sb01 : " + (nd.aux() == 0 ? "2'sb00" : "2'sb11") + ";");
            case BNOT -> line("assign " + v(id) + " = !" + v(a[0]) + ";");
            case MUXT -> line("assign " + v(id) + " = (" + dr(a[0], 0) + " == 2'sb11) ? " + v(a[1]) + " : (" + dr(a[0], 0) + " == 2'sb00) ? " + v(a[2]) + " : " + v(a[3]) + ";");
            case MUXB -> line("assign " + v(id) + " = " + v(a[0]) + " ? " + v(a[1]) + " : " + v(a[2]) + ";");
            default -> throw new IllegalStateException("op " + nd.op());
        }
    }
}
