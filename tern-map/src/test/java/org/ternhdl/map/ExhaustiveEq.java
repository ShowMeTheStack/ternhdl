package org.ternhdl.map;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.stdlib.Benchmarks;

/**
 * Exhaustive equivalence by simulation: RTL twin and mapped-netlist twin in one Verilator model, every legal input
 * combination enumerated in C++ (combinational designs with <= 20 input trits). args: benchmark threads (not a test).
 */
public final class ExhaustiveEq {
    public static void main(String[] a) throws Exception {
        String name = a[0];
        int threads = Integer.parseInt(a[1]);
        Design rtl = Benchmarks.all().get(name).get();
        Netlist mapped = Mapper.mapToFixpoint(Lower.lower(rtl).netlist().sweep(), new ExactDb(ExactDb.gtFull()));
        Design md = NetlistDesign.of(mapped, NetlistDesign.portTypes(rtl)).design();
        Path dir = Path.of(System.getenv().getOrDefault("TERN_WORK", "/tmp"), "exhaustive", name);
        Files.createDirectories(dir);
        Encoding e = Encoding.E1;
        Files.writeString(dir.resolve("a.sv"), SvEmitter.emit(rtl, new SvEmitter.Options(e, false, false)));
        Files.writeString(dir.resolve("b.sv"), SvEmitter.emit(md, new SvEmitter.Options(e, false, false)));
        List<String> ins = new ArrayList<>(rtl.inputs().keySet()), outs = new ArrayList<>(rtl.outputs().keySet());
        int totalTrits = 0;
        for (String in : ins) totalTrits += rtl.node(rtl.inputs().get(in)).type().width();
        StringBuilder top = new StringBuilder("module eqtop (");
        List<String> ports = new ArrayList<>();
        for (String in : ins) ports.add("input logic [" + (2 * rtl.node(rtl.inputs().get(in)).type().width() - 1) + ":0] " + in);
        ports.add("output logic diff");
        top.append(String.join(", ", ports)).append(");\n");
        for (String o : outs) { int w = 2 * rtl.node(rtl.outputs().get(o)).type().width(); top.append("  logic [").append(w - 1).append(":0] a_").append(o).append(", b_").append(o).append(";\n"); }
        for (String side : new String[]{"a", "b"}) {
            List<String> conn = new ArrayList<>();
            for (String in : ins) conn.add("." + in + "(" + in + ")");
            for (String o : outs) conn.add("." + o + "(" + side + "_" + o + ")");
            top.append("  ").append(side.equals("a") ? rtl.name : md.name).append(" u_").append(side).append(" (").append(String.join(", ", conn)).append(");\n");
        }
        StringBuilder cmp = new StringBuilder("1'b0");
        for (String o : outs) cmp.append(" | (a_").append(o).append(" != b_").append(o).append(")");
        top.append("  assign diff = ").append(cmp).append(";\nendmodule\n");
        Files.writeString(dir.resolve("eqtop.sv"), top.toString());
        // C++: thread t handles values of the first input trit range split; digits encoded with E1 (N=10, O=00, P=01)
        StringBuilder c = new StringBuilder("#include \"Veqtop.h\"\n#include \"verilated.h\"\n#include <thread>\n#include <atomic>\n#include <vector>\n#include <cstdio>\n#include <cstdint>\n");
        c.append("static const uint64_t CODE[3] = {2, 0, 1};\nstd::atomic<long long> bad(0), done(0);\n");
        c.append("void work(int t, int T) {\n  VerilatedContext* ctx = new VerilatedContext; Veqtop* m = new Veqtop{ctx};\n");
        c.append("  const long long total = ").append((long) Math.pow(3, totalTrits)).append("LL;\n");
        c.append("  for (long long k = t; k < total; k += T) {\n    long long v = k;\n");
        for (String in : ins) {
            int w = rtl.node(rtl.inputs().get(in)).type().width();
            c.append("    { uint64_t x = 0; for (int i = 0; i < ").append(w).append("; i++) { x |= CODE[v % 3] << (2 * i); v /= 3; } m->").append(in).append(" = x; }\n");
        }
        c.append("    m->eval();\n    if (m->diff) { if (bad++ < 5) printf(\"DIFF at %lld\\n\", k); }\n    done++;\n  }\n  delete m; delete ctx;\n}\n");
        c.append("int main() { int T = ").append(threads).append("; std::vector<std::thread> th; for (int t = 0; t < T; t++) th.emplace_back(work, t, T); for (auto& x : th) x.join();\n");
        c.append("  printf(\"EXHAUSTIVE checked=%lld mismatches=%lld\\n\", (long long) done, (long long) bad); return bad ? 1 : 0; }\n");
        Files.writeString(dir.resolve("main.cpp"), c.toString());
        String cmd = "cd " + dir + " && verilator --cc --exe --build -O3 -j 16 -Wno-fatal --threads 1 --top-module eqtop a.sv b.sv eqtop.sv main.cpp -CFLAGS -O2 -LDFLAGS -pthread -o eq > build.log 2>&1 && /usr/bin/time -f 'elapsed %e s' ./obj_dir/eq";
        Process p = new ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start();
        System.out.print(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        System.out.println("EXHAUSTIVE " + name + " inputs=" + totalTrits + " trits, exit=" + p.waitFor() + ", mapped " + mapped.stats());
    }
}
