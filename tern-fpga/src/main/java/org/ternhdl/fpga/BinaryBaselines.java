package org.ternhdl.fpga;

import java.util.*;

/**
 * B4: hand-written binary SystemVerilog baselines at matched information width (n trits ~ ceil(n log2 3) bits).
 * Each entry: module text and its port list {name, width, dir}. Two's complement, same operation sets as the
 * ternary benchmarks.
 */
public final class BinaryBaselines {
    public record Module(String name, String sv, List<String[]> ports, boolean seq) { }

    private BinaryBaselines() { }

    public static int bitsFor(int trits) { return (int) Math.ceil(trits * Math.log(3) / Math.log(2)); }

    public static Map<String, Module> all() {
        Map<String, Module> m = new LinkedHashMap<>();
        for (int t : new int[]{9, 27}) { int b = bitsFor(t); m.put("bin_add" + b, add(b)); }
        int b9 = bitsFor(9);
        m.put("bin_alu" + b9, alu(b9));
        m.put("bin_mul" + b9, mul(b9));
        m.put("bin_dot4x" + b9, dot(b9));
        m.put("bin_cmp" + bitsFor(27), cmp(bitsFor(27)));
        m.put("bin_counter" + b9, counter(b9));
        m.put("bin_regfile9x" + b9, regfile(b9));
        m.put("bin_fifo3x" + b9, fifo(b9));
        return m;
    }

    static List<String[]> ports(String... p) { List<String[]> l = new ArrayList<>(); for (int i = 0; i < p.length; i += 3) l.add(new String[]{p[i], p[i + 1], p[i + 2]}); return l; }

    static Module add(int n) {
        String nm = "bin_add" + n;
        String sv = "module " + nm + " (input logic signed [" + (n - 1) + ":0] a, b, input logic cin, output logic signed [" + n + ":0] s);\n  assign s = a + b + cin;\nendmodule\n";
        return new Module(nm, sv, ports("a", "" + n, "in", "b", "" + n, "in", "cin", "1", "in", "s", "" + (n + 1), "out"), false);
    }

    static Module alu(int n) {
        String nm = "bin_alu" + n, w = "[" + (n - 1) + ":0]";
        String sv = "module " + nm + " (input logic signed " + w + " a, b, input logic [1:0] op1, op2, output logic signed " + w + " y);\n"
            + "  always_comb case ({op1, op2})\n    4'b0000: y = a + b;\n    4'b0001: y = a - b;\n    4'b0010: y = (a < b) ? a : b;\n"
            + "    4'b0100: y = (a > b) ? a : b;\n    4'b0101: y = a ^ b;\n    4'b0110: y = a & b;\n    4'b1000: y = a <<< 1;\n    4'b1001: y = a >>> 1;\n"
            + "    4'b1010: y = (a < b) ? -1 : (a > b) ? 1 : 0;\n    default: y = '0;\n  endcase\nendmodule\n";
        return new Module(nm, sv, ports("a", "" + n, "in", "b", "" + n, "in", "op1", "2", "in", "op2", "2", "in", "y", "" + n, "out"), false);
    }

    static Module mul(int n) {
        String nm = "bin_mul" + n;
        String sv = "module " + nm + " (input logic signed [" + (n - 1) + ":0] a, b, output logic signed [" + (2 * n - 1) + ":0] p);\n  assign p = a * b;\nendmodule\n";
        return new Module(nm, sv, ports("a", "" + n, "in", "b", "" + n, "in", "p", "" + (2 * n), "out"), false);
    }

    static Module dot(int n) {
        String nm = "bin_dot4x" + n;
        StringBuilder sv = new StringBuilder("module " + nm + " (");
        for (int i = 0; i < 4; i++) sv.append("input logic signed [" + (n - 1) + ":0] x" + i + ", input logic signed [1:0] w" + i + ", ");
        sv.append("output logic signed [" + (n + 2) + ":0] y);\n  always_comb begin\n    y = '0;\n");
        for (int i = 0; i < 4; i++) sv.append("    if (w" + i + " == 2'sb01) y = y + x" + i + "; else if (w" + i + " == 2'sb11) y = y - x" + i + ";\n");
        sv.append("  end\nendmodule\n");
        List<String[]> p = new ArrayList<>();
        for (int i = 0; i < 4; i++) { p.add(new String[]{"x" + i, "" + n, "in"}); p.add(new String[]{"w" + i, "2", "in"}); }
        p.add(new String[]{"y", "" + (n + 3), "out"});
        return new Module(nm, sv.toString(), p, false);
    }

    static Module cmp(int n) {
        String nm = "bin_cmp" + n;
        String sv = "module " + nm + " (input logic signed [" + (n - 1) + ":0] a, b, output logic [1:0] k);\n  assign k = (a < b) ? 2'b11 : (a > b) ? 2'b01 : 2'b00;\nendmodule\n";
        return new Module(nm, sv, ports("a", "" + n, "in", "b", "" + n, "in", "k", "2", "out"), false);
    }

    static Module counter(int n) {
        String nm = "bin_counter" + n;
        String sv = "module " + nm + " (input logic clk, rst, en, output logic [" + (n - 1) + ":0] q);\n  always_ff @(posedge clk) if (rst) q <= '0; else if (en) q <= q + 1'b1;\nendmodule\n";
        return new Module(nm, sv, ports("rst", "1", "in", "en", "1", "in", "q", "" + n, "out"), true);
    }

    static Module regfile(int n) {
        String nm = "bin_regfile9x" + n;
        String sv = "module " + nm + " (input logic clk, rst, we, input logic [3:0] wa, ra, rb, input logic [" + (n - 1) + ":0] wd, output logic [" + (n - 1) + ":0] qa, qb);\n"
            + "  logic [" + (n - 1) + ":0] r [0:8];\n  always_ff @(posedge clk) if (rst) begin for (int i = 0; i < 9; i++) r[i] <= '0; end else if (we && wa < 9) r[wa] <= wd;\n"
            + "  assign qa = (ra < 9) ? r[ra] : '0;\n  assign qb = (rb < 9) ? r[rb] : '0;\nendmodule\n";
        return new Module(nm, sv, ports("rst", "1", "in", "we", "1", "in", "wa", "4", "in", "ra", "4", "in", "rb", "4", "in", "wd", "" + n, "in", "qa", "" + n, "out", "qb", "" + n, "out"), true);
    }

    static Module fifo(int n) {
        String nm = "bin_fifo3x" + n;
        String sv = "module " + nm + " (input logic clk, rst, push, pop, input logic [" + (n - 1) + ":0] din, output logic [" + (n - 1) + ":0] dout, output logic full, empty);\n"
            + "  logic [" + (n - 1) + ":0] m [0:2];\n  logic [1:0] wp, rp, cnt;\n  assign full = cnt == 2'd3;\n  assign empty = cnt == 2'd0;\n"
            + "  wire dpush = push && !full, dpop = pop && !empty;\n"
            + "  always_ff @(posedge clk) if (rst) begin wp <= 0; rp <= 0; cnt <= 0; end else begin\n"
            + "    if (dpush) begin m[wp] <= din; wp <= (wp == 2) ? 0 : wp + 1; end\n    if (dpop) rp <= (rp == 2) ? 0 : rp + 1;\n    cnt <= cnt + dpush - dpop;\n  end\n"
            + "  assign dout = m[rp];\nendmodule\n";
        return new Module(nm, sv, ports("rst", "1", "in", "push", "1", "in", "pop", "1", "in", "din", "" + n, "in", "dout", "" + n, "out", "full", "1", "out", "empty", "1", "out"), true);
    }
}
