package org.ternhdl.formal;

import java.nio.file.Path;
import java.util.*;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;

/** High-level formal checks built from the emitters. */
public final class Equivalence {
    private Equivalence() { }

    /** Default engine, chosen from an engine comparison on the benchmark miters: ABC PDR. */
    public static final String DEFAULT_ENGINE = "abc pdr";

    /** T2 for one design: the encoded twin equals the independent reference model on every node, for all legal inputs. */
    public static Sby.Result twinRefinesReference(Design d, Encoding enc, String solver, int timeoutSec, String tag) throws Exception {
        return twinRefinesReference(d, enc, RefEmitter.Arith.DIGIT_SERIAL, solver, timeoutSec, tag);
    }

    public static Sby.Result twinRefinesReference(Design d, Encoding enc, RefEmitter.Arith arith, String solver, int timeoutSec, String tag) throws Exception {
        return twinRefinesReference(d, enc, arith, SvEmitter.AdderStyle.RIPPLE, solver, timeoutSec, tag);
    }

    public static Sby.Result twinRefinesReference(Design d, Encoding enc, RefEmitter.Arith arith, SvEmitter.AdderStyle adders, String solver, int timeoutSec, String tag) throws Exception {
        d.finish();
        String twin = SvEmitter.emit(d, new SvEmitter.Options(enc, true, true, adders));
        String ref = RefEmitter.emit(d, enc, d.name + "_ref", true, arith);
        boolean clk = twin.contains("input logic clk");
        String miter = Miter.emit(d, enc, d.name, d.name + "_ref", true, clk);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("twin.sv", twin); files.put("ref.sv", ref); files.put("miter.sv", miter);
        return Sby.prove(Sby.workRoot().resolve(tag), files, "t_miter", solver, clk ? 2 : 1, timeoutSec);
    }

    /** Twin of one design against the reference model of another with the same interface (outputs only). */
    public static Sby.Result twinVsReference(Design twinD, Design refD, Encoding enc, String solver, int timeoutSec, String tag) throws Exception {
        String twin = SvEmitter.emit(twinD, new SvEmitter.Options(enc, true, false));
        String ref = RefEmitter.emit(refD, enc, refD.name + "_ref", false);
        boolean clk = twin.contains("input logic clk");
        String miter = Miter.emit(twinD, enc, twinD.name, refD.name + "_ref", false, clk);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("twin.sv", twin); files.put("ref.sv", ref); files.put("miter.sv", miter);
        return Sby.prove(Sby.workRoot().resolve(tag), files, "t_miter", solver, clk ? 2 : 1, timeoutSec);
    }

    /**
     * Proves (mode prove: k-induction) or bounded-checks (mode bmc) the design's own assertions on the twin, assuming
     * legal input codes, legal initial register codes, design assumptions, and optionally rst = 1 in the first cycle.
     */
    public static Sby.Result properties(Design d, Encoding enc, boolean prove, int depth, boolean resetFirst, String solver, int timeoutSec, String tag) throws Exception {
        d.finish();
        String twin = SvEmitter.emit(d, new SvEmitter.Options(enc, true, true));
        boolean clk = twin.contains("input logic clk");
        String ill = "2'b" + (enc.illegal >> 1 & 1) + (enc.illegal & 1);
        StringBuilder w = new StringBuilder("module t_prop (\n");
        List<String> ports = new ArrayList<>();
        if (clk) ports.add("input logic clk");
        for (var en : d.inputs().entrySet()) { Type t = d.node(en.getValue()).type(); ports.add("input logic " + (t.isBit() ? "" : "[" + (2 * t.width() - 1) + ":0] ") + en.getKey()); }
        w.append("  ").append(String.join(",\n  ", ports)).append("\n);\n");
        List<String> conn = new ArrayList<>();
        if (clk) conn.add(".clk(clk)");
        for (String in : d.inputs().keySet()) conn.add("." + in + "(" + in + ")");
        for (Node nd : d.nodes()) {
            Type t = nd.type();
            w.append("  logic ").append(t.isBit() ? "" : "[" + (2 * t.width() - 1) + ":0] ").append("t_d").append(nd.id()).append(";\n");
            conn.add("." + SvEmitter.dbg(nd.id()) + "(t_d" + nd.id() + ")");
        }
        for (var en : d.outputs().entrySet()) { Type t = d.node(en.getValue()).type(); w.append("  logic ").append(t.isBit() ? "" : "[" + (2 * t.width() - 1) + ":0] ").append("t_o_").append(en.getKey()).append(";\n"); conn.add("." + en.getKey() + "(t_o_" + en.getKey() + ")"); }
        w.append("  ").append(d.name).append(" u (").append(String.join(", ", conn)).append(");\n  always @* begin\n");
        for (var en : d.inputs().entrySet()) {
            Type t = d.node(en.getValue()).type();
            if (!t.isBit()) for (int i = 0; i < t.width(); i++) w.append("    assume (").append(en.getKey()).append("[").append(2 * i + 1).append(":").append(2 * i).append("] != ").append(ill).append(");\n");
        }
        w.append("  end\n");
        if (clk) {
            w.append("  logic t_init = 1'b1;\n  always @(posedge clk) t_init <= 1'b0;\n  always @* if (t_init) begin\n");
            for (Node nd : d.nodes()) if (nd.op() == Op.REG && !nd.type().isBit())
                for (int i = 0; i < nd.type().width(); i++) w.append("    assume (t_d").append(nd.id()).append("[").append(2 * i + 1).append(":").append(2 * i).append("] != ").append(ill).append(");\n");
            if (resetFirst) w.append("    assume (rst);\n");
            w.append("  end\n");
        }
        w.append("endmodule\n");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("twin.sv", twin); files.put("prop.sv", w.toString());
        return Sby.prove(Sby.workRoot().resolve(tag), files, "t_prop", solver, depth, timeoutSec, prove ? "prove" : "bmc");
    }

    /** Sequential equivalence of two designs with the same ports and paired register digits (see Miter.emitPaired). */
    public static Sby.Result pairedEquivalent(Design l, Design r, List<int[]> pairs, Encoding enc, String engine, int timeoutSec, String tag) throws Exception {
        if (l.name.equals(r.name)) throw new IllegalArgumentException("designs need different module names");
        String tl = SvEmitter.emit(l, new SvEmitter.Options(enc, true, true));
        String tr = SvEmitter.emit(r, new SvEmitter.Options(enc, true, true));
        boolean clk = tl.contains("input logic clk") || tr.contains("input logic clk");
        if (tl.contains("input logic clk") != tr.contains("input logic clk")) throw new IllegalArgumentException("clock mismatch");
        String miter = Miter.emitPaired(l, r, enc, l.name, r.name, pairs, clk);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("l.sv", tl); files.put("r.sv", tr); files.put("miter.sv", miter);
        return Sby.prove(Sby.workRoot().resolve(tag), files, "t_miter", engine, clk ? 2 : 1, timeoutSec);
    }

    /** Output equivalence of two designs with the same interface (both emitted as twins). */
    public static Sby.Result twinsEquivalent(Design a, Design b, Encoding enc, String solver, int timeoutSec, String tag) throws Exception {
        String ta = SvEmitter.emit(a, new SvEmitter.Options(enc, false, false));
        String tb = SvEmitter.emit(b, new SvEmitter.Options(enc, false, false));
        boolean clk = ta.contains("input logic clk") || tb.contains("input logic clk");
        String miter = Miter.emit(a, enc, a.name, b.name, false, clk);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a.sv", ta); files.put("b.sv", tb); files.put("miter.sv", miter);
        return Sby.prove(Sby.workRoot().resolve(tag), files, "t_miter", solver, clk ? 20 : 1, timeoutSec);
    }
}
