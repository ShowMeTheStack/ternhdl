package org.ternhdl.fpga;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.ternhdl.core.*;
import org.ternhdl.encode.Encoding;
import org.ternhdl.sim.Interpreter;

/**
 * Lockstep test of the board harness. The same command stream ("W addr data" / "R addr") is executed either by a
 * Verilator AXI master (target "sim") or by xsct over JTAG on the ZedBoard (target "board"); results are compared
 * with the native Interpreter cycle by cycle.
 */
public final class BoardRunner {
    public record Report(int cycles, int digitsCompared, List<String> mismatches, double seconds) { }

    private BoardRunner() { }

    public static long encode(Encoding e, Type t, Logic3[] v) {
        if (t.isBit()) return v[0] == Logic3.P ? 1 : 0;
        long x = 0;
        for (int i = 0; i < v.length; i++) x |= (long) e.code(v[i]) << (2 * i);
        return x;
    }

    /** Builds the command stream and the expected outputs for random legal stimulus (reset asserted in cycle 0). */
    public static Report run(Design d, Encoding e, int cycles, long seed, String target, Path dir) throws Exception {
        BoardSoc.Layout l = BoardSoc.layout(d);
        Interpreter it = new Interpreter(d);
        Random r = new Random(seed);
        List<String> cmds = new ArrayList<>();
        List<Map<String, Logic3[]>> expected = new ArrayList<>();
        for (int c = 0; c < cycles; c++) {
            long[] words = new long[l.inWords()];
            for (BoardSoc.Field f : l.in()) {
                Type t = d.node(d.inputs().get(f.port())).type();
                Logic3[] v = new Logic3[t.width()];
                for (int i = 0; i < v.length; i++) v[i] = t.isBit() ? (r.nextBoolean() ? Logic3.P : Logic3.O) : Logic3.ofBal(r.nextInt(3) - 1);
                if (f.port().equals("rst")) v[0] = c == 0 || r.nextInt(16) == 0 ? Logic3.P : Logic3.O;
                it.poke(f.port(), v);
                long enc = encode(e, t, v);
                for (int bit = 0; bit < f.width(); bit++) if ((enc >>> bit & 1) != 0) { int g = BoardSoc.offset(f) + bit; words[g / 32] |= 1L << (g % 32); }
            }
            for (int k = 0; k < words.length; k++) cmds.add(String.format("W %x %x", 0x100 + 4 * k, words[k]));
            for (int k = 0; k < l.outWords(); k++) cmds.add(String.format("R %x", 0x200 + 4 * k));
            Map<String, Logic3[]> exp = new LinkedHashMap<>();
            for (BoardSoc.Field f : l.out()) exp.put(f.port(), it.peekVec(f.port()));
            expected.add(exp);
            cmds.add("W 0 1");
            it.step();
        }
        cmds.add("R 4");
        long t0 = System.nanoTime();
        List<Long> reads = target.equals("sim") ? simulate(dir, cmds) : board(dir, cmds);
        double sec = (System.nanoTime() - t0) / 1e9;
        List<String> bad = new ArrayList<>();
        int cmp = 0;
        if (reads.size() != cycles * l.outWords() + 1) { bad.add("expected " + (cycles * l.outWords() + 1) + " reads, got " + reads.size()); return new Report(cycles, 0, bad, sec); }
        for (int c = 0; c < cycles; c++) {
            for (BoardSoc.Field f : l.out()) {
                long v = 0;
                for (int bit = 0; bit < f.width(); bit++) { int g = BoardSoc.offset(f) + bit; if ((reads.get(c * l.outWords() + g / 32) >>> (g % 32) & 1) != 0) v |= 1L << bit; }
                Type t = d.node(d.outputs().get(f.port())).type();
                Logic3[] ex = expected.get(c).get(f.port());
                for (int i = 0; i < ex.length; i++) {
                    if (!ex[i].isLevel()) continue;
                    cmp++;
                    Logic3 got = t.isBit() ? ((v & 1) != 0 ? Logic3.P : Logic3.O) : e.decode((int) (v >>> (2 * i) & 3));
                    if (got != ex[i]) bad.add("cycle " + c + " " + f.port() + "[" + i + "] expected " + ex[i] + " got " + got);
                }
            }
        }
        long steps = reads.get(reads.size() - 1);
        if (steps != cycles) bad.add("step counter " + steps + " != " + cycles);
        return new Report(cycles, cmp, bad, sec);
    }

    static List<Long> parse(String out) {
        List<Long> r = new ArrayList<>();
        for (String line : out.split("\n")) if (line.startsWith("RD ")) r.add(Long.parseUnsignedLong(line.substring(3).trim(), 16));
        return r;
    }

    /** Verilator AXI master over board_top. */
    public static List<Long> simulate(Path dir, List<String> cmds) throws Exception {
        String main = """
            #include "Vboard_top.h"
            #include "verilated.h"
            #include <cstdio>
            #include <cstring>
            static Vboard_top* t;
            static void tick() { t->aclk = 0; t->eval(); t->aclk = 1; t->eval(); }
            static void wr(unsigned a, unsigned d) {
              t->s_axi_awaddr = a; t->s_axi_awvalid = 1; t->s_axi_wdata = d; t->s_axi_wstrb = 15; t->s_axi_wvalid = 1; t->s_axi_bready = 1;
              bool aw = false, w = false;
              for (int i = 0; i < 100 && !(aw && w); i++) { t->eval(); bool a1 = t->s_axi_awready, w1 = t->s_axi_wready; tick();
                if (a1 && !aw) { aw = true; t->s_axi_awvalid = 0; } if (w1 && !w) { w = true; t->s_axi_wvalid = 0; } }
              for (int i = 0; i < 100; i++) { t->eval(); bool b = t->s_axi_bvalid; tick(); if (b) break; }
              t->s_axi_bready = 0;
            }
            static unsigned rd(unsigned a) {
              t->s_axi_araddr = a; t->s_axi_arvalid = 1; t->s_axi_rready = 1;
              for (int i = 0; i < 100; i++) { t->eval(); bool r = t->s_axi_arready; tick(); if (r) { t->s_axi_arvalid = 0; break; } }
              unsigned v = 0;
              for (int i = 0; i < 100; i++) { t->eval(); if (t->s_axi_rvalid) { v = t->s_axi_rdata; tick(); break; } tick(); }
              t->s_axi_rready = 0;
              return v;
            }
            int main(int argc, char** argv) {
              VerilatedContext* ctx = new VerilatedContext; ctx->commandArgs(argc, argv); t = new Vboard_top{ctx};
              t->aresetn = 0; for (int i = 0; i < 4; i++) tick(); t->aresetn = 1; tick();
              char op[4]; unsigned a, d;
              while (scanf("%3s", op) == 1) {
                if (op[0] == 'W') { if (scanf("%x %x", &a, &d) != 2) break; wr(a, d); }
                else if (op[0] == 'D') { if (scanf("%x", &a) != 1) break; for (unsigned i = 0; i < a + 4; i++) tick(); }
                else { if (scanf("%x", &a) != 1) break; printf("RD %x\\n", rd(a)); }
              }
              t->final(); delete t; delete ctx; return 0;
            }
            """;
        Files.writeString(dir.resolve("axi_main.cpp"), main);
        run(dir, "verilator --cc --exe --build -O1 -j 8 -Wno-fatal --top-module board_top dut.sv board_top.sv axi_main.cpp -o axisim > axisim_build.log 2>&1", null);
        return parse(run(dir, "./obj_dir/axisim", String.join("\n", cmds) + "\n"));
    }

    /** xsct over JTAG in docker (USB passthrough): program, ps7_init, then mwr/mrd. Board must be idle. */
    public static List<Long> board(Path dir, List<String> cmds) throws Exception {
        StringBuilder t = new StringBuilder("connect\ntargets -set -nocase -filter {name =~ \"APU*\"}\nrst -system\nafter 500\n");
        t.append("targets -set -nocase -filter {name =~ \"xc7z020*\"}\nfpga -file soc.bit\n");
        t.append("targets -set -nocase -filter {name =~ \"APU*\"}\nloadhw -hw soc.xsa -mem-ranges [list {0x40000000 0xbfffffff}]\nsource ps7_init.tcl\nps7_init\nps7_post_config\n");
        t.append("targets -set -nocase -filter {name =~ \"*Cortex-A9*#0\"}\ncatch {stop}\n");
        for (String c : cmds) {
            String[] p = c.split(" ");
            long addr = 0x43C00000L + Long.parseLong(p[1], 16);
            if (p[0].equals("D")) { t.append("after 20\n"); continue; }
            if (p[0].equals("W")) t.append(String.format("mwr 0x%x 0x%s%n", addr, p[2]));
            else t.append(String.format("puts \"RD [format %%x [mrd -value 0x%x]]\"%n", addr));
        }
        t.append("disconnect\n");
        Files.writeString(dir.resolve("run.tcl"), t.toString());
        run(dir, "unzip -o -q soc.xsa ps7_init.tcl", null);
        String fake = System.getProperty("user.home") + "/ternhdl-viv/fakebin";
        String out = run(dir, "sudo docker run --rm -i --privileged --net=host -v /dev/bus/usb:/dev/bus/usb -v " + fake + ":/fakebin -v " + dir + ":/w -w /w kkrizka/vivado:2020.1 bash -c "
            + "'source /opt/Xilinx/Vivado/2020.1/settings64.sh >/dev/null; export DISPLAY=:99; export PATH=/fakebin:/opt/Xilinx/Vitis/2020.1/bin:$PATH; xsct run.tcl' 2>&1 | tee xsct.log", null);
        return parse(out);
    }

    static String run(Path dir, String cmd, String stdin) throws Exception {
        Process p = new ProcessBuilder("bash", "-c", "cd " + dir + " && " + cmd).redirectErrorStream(true).start();
        if (stdin != null) { p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8)); }
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(3600, TimeUnit.SECONDS)) p.destroyForcibly();
        return out;
    }

    public static void main(String[] a) throws Exception {
        String name = a[0], target = a[1];
        Encoding enc = Encoding.valueOf(a.length > 2 ? a[2] : "E1");
        int cycles = a.length > 3 ? Integer.parseInt(a[3]) : 200;
        Design d = org.ternhdl.stdlib.Benchmarks.all().get(name).get();
        Report r = run(d, enc, cycles, 42, target, BoardBuild.dir(name, enc));
        System.out.printf("BOARDRUN %s %s %s cycles=%d digits=%d mismatches=%d %.1fs %s%n", name, enc, target, r.cycles(), r.digitsCompared(), r.mismatches().size(), r.seconds(),
            r.mismatches().subList(0, Math.min(5, r.mismatches().size())));
    }
}
