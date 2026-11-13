package org.ternhdl.fpga;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.stdlib.Benchmarks;

/** Encoding study: ternary benchmarks in E0/E1/E2 and binary baselines through Vivado OOC (not a test). */
public final class EncodingStudy {
    public static void main(String[] a) throws Exception {
        Path root = Path.of(System.getProperty("user.home"), "ternhdl-viv", "ooc");   // docker cannot mount /extra1 here
        double period = 4.0;
        List<String> tern = List.of("ripple9", "ripple27", "prefix27", "csel27", "alu9", "mul9", "dot4x9", "cmp27", "counter9", "regfile9x9", "fifo3x9");
        String[] cpuSets = {"0-3", "4-7", "8-11", "12-15", "16-19", "20-23"};
        ExecutorService ex = Executors.newFixedThreadPool(cpuSets.length);
        BlockingQueue<String> cpus = new LinkedBlockingQueue<>(List.of(cpuSets));
        Map<String, Future<VivadoOoc.Qor>> fs = new LinkedHashMap<>();
        for (String nm : tern) for (Encoding e : Encoding.values()) {
            String key = nm + "_" + e;
            fs.put(key, ex.submit(() -> {
                Design d = Benchmarks.all().get(nm).get();
                d.finish();
                List<String[]> ports = new ArrayList<>();
                for (var en : d.inputs().entrySet()) { Type t = d.node(en.getValue()).type(); ports.add(new String[]{en.getKey(), "" + (t.isBit() ? 1 : 2 * t.width()), "in"}); }
                for (var en : d.outputs().entrySet()) { Type t = d.node(en.getValue()).type(); ports.add(new String[]{en.getKey(), "" + (t.isBit() ? 1 : 2 * t.width()), "out"}); }
                String sv = SvEmitter.emit(d, new SvEmitter.Options(e, false, false));
                boolean seq = sv.contains("input logic clk");
                String c = cpus.take();
                try { return VivadoOoc.run(key, sv, d.name, ports, seq, period, root, c); } finally { cpus.put(c); }
            }));
        }
        for (var bm : BinaryBaselines.all().values()) {
            fs.put(bm.name(), ex.submit(() -> {
                String c = cpus.take();
                try { return VivadoOoc.run(bm.name(), bm.sv(), bm.name(), bm.ports(), bm.seq(), period, root, c); } finally { cpus.put(c); }
            }));
        }
        StringBuilder sb = new StringBuilder("# Encoding study (Vivado 2020.1, xc7z020clg484-1, out of context, all ports registered, max_dsp 0, 4 ns target)\n\n");
        sb.append("| Design | LUT | FF | CARRY4 | WNS (ns) | Fmax (MHz) |\n|---|---|---|---|---|---|\n");
        for (var f : fs.entrySet()) {
            VivadoOoc.Qor q;
            try { q = f.getValue().get(); } catch (ExecutionException e) { q = new VivadoOoc.Qor(f.getKey(), 0, 0, 0, 0, period, e.getCause().toString()); }
            String row = q.error() != null ? "| " + f.getKey() + " | ERROR " + q.error().replace("\n", " ").substring(0, Math.min(200, q.error().length())) + " |"
                : String.format(Locale.ROOT, "| %s | %d | %d | %d | %.3f | %.1f |", q.name(), q.luts(), q.ffs(), q.carry4(), q.wnsNs(), q.fmaxMhz());
            sb.append(row).append('\n');
            System.out.println(row);
        }
        ex.shutdown();
        Files.writeString(Path.of("bench/results/encoding-study.txt"), sb.toString());
    }
}
