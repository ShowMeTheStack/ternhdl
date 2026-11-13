package org.ternhdl.map;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.formal.*;
import org.ternhdl.stdlib.Benchmarks;

/** Mapping benchmark (not a test): writes bench/results/mapping.txt. */
public final class MappingBench {
    static List<int[]> pairs(Design rtl, Lower.Result lr, NetlistDesign.Result nd) {
        List<int[]> p = new ArrayList<>();
        int k = 0;
        Map<Integer, Integer> ordinal = new HashMap<>();
        for (int i = 0; i < lr.netlist().insts().size(); i++) if (lr.netlist().insts().get(i).cell == Cell.DFF) ordinal.put(i, k++);
        for (int[] rd : lr.regDigits()) p.add(new int[]{rtl.regs().get(rd[0]).readNode(), rd[1], nd.dffRegNode()[ordinal.get(rd[2])], 0});
        return p;
    }

    static String yosys(String sv, String top, Path dir, String tag) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve(tag + ".sv");
        Files.writeString(f, sv);
        String script = "read_verilog -sv " + f + "; synth -top " + top + " -flatten; abc -g simple; opt_clean; tee -o " + dir.resolve(tag + "_g.txt")
            + " stat; design -reset; read_verilog -sv " + f + "; synth_xilinx -top " + top + " -flatten -noiopad; tee -o " + dir.resolve(tag + "_x.txt") + " stat";
        Process p = new ProcessBuilder("yosys", "-q", "-p", script).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) return "err/err";
        return cells(Files.readString(dir.resolve(tag + "_g.txt"))) + "/" + luts(Files.readString(dir.resolve(tag + "_x.txt")));
    }

    static String cells(String stat) {
        for (String l : stat.split("\n")) if (l.trim().startsWith("Number of cells:")) return l.trim().split("\\s+")[3];
        return "?";
    }

    static String luts(String stat) {
        int n = 0;
        for (String l : stat.split("\n")) { String t = l.trim(); if (t.startsWith("LUT")) n += Integer.parseInt(t.split("\\s+")[1]); }
        return String.valueOf(n);
    }

    public static void main(String[] a) throws Exception {
        ExactDb db = new ExactDb(ExactDb.gtFull());
        Path ydir = Path.of(System.getenv().getOrDefault("TERN_WORK", "/tmp"), "mapyosys");
        ExecutorService ex = Executors.newFixedThreadPool(24);
        Map<String, Future<String>> rows = new LinkedHashMap<>();
        for (var en : Benchmarks.all().entrySet()) {
            rows.put(en.getKey(), ex.submit(() -> {
                Design rtl = en.getValue().get();
                Lower.Result lr = Lower.lower(rtl);
                Netlist b3 = lr.netlist().sweep();
                Netlist b1 = MuxBaseline.of(b3);
                Netlist ours = Mapper.mapToFixpoint(b3, db);
                NetlistDesign.Result nd = NetlistDesign.of(ours, NetlistDesign.portTypes(rtl));
                Sby.Result eq = Equivalence.pairedEquivalent(rtl, nd.design(), pairs(rtl, lr, nd), Encoding.E1, Equivalence.DEFAULT_ENGINE, 1800, "map-" + en.getKey());
                String e1 = yosys(SvEmitter.emit(rtl, new SvEmitter.Options(Encoding.E1, false, false)), rtl.name, ydir, en.getKey() + "_E1");
                String e2 = yosys(SvEmitter.emit(rtl, new SvEmitter.Options(Encoding.E2, false, false)), rtl.name, ydir, en.getKey() + "_E2");
                double red = 100.0 * (b3.cellCount() - ours.cellCount()) / Math.max(1, b3.cellCount());
                return String.format("| %s | %d | %d / %d | %d / %d | %d / %d / %d | %.1f%% | %s %.1fs | %s | %s |", en.getKey(), ours.dffCount(),
                    b1.cellCount(), b1.depth(), b3.cellCount(), b3.depth(), ours.cellCount(), ours.depth(), ours.area(), red, eq.status(), eq.seconds(), e1, e2);
            }));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Mapping results\n\n");
        sb.append("Placeholder area model (unary 1, binary 2, MUX3 3, DFF 4). Cells exclude TIE and DFF. ");
        sb.append("B1 = MUX-based baseline; B3 = direct lowering (Park-style pattern mapping) after dead-code sweep; ");
        sb.append("Ours = exact-database cut mapping of B3. Equiv = PDR equivalence of Ours vs RTL (E1). ");
        sb.append("B2 = binary-encoded twin through Yosys: generic 2-input gates (abc -g simple) / Xilinx LUTs.\n\n");
        sb.append("| Design | DFF | B1 cells / depth | B3 cells / depth | Ours cells / depth / area | Ours vs B3 | Equiv | B2 E1 gates/LUTs | B2 E2 gates/LUTs |\n|---|---|---|---|---|---|---|---|---|\n");
        for (var r : rows.entrySet()) {
            String row;
            try { row = r.getValue().get(); } catch (ExecutionException e) { row = "| " + r.getKey() + " | ERROR " + e.getCause() + " |"; }
            sb.append(row).append('\n');
            System.out.println(row);
        }
        ex.shutdown();
        Files.writeString(Path.of("bench/results/mapping.txt"), sb.toString());
    }
}
