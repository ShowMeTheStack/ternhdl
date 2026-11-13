package org.ternhdl.asic;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.ternhdl.map.*;
import org.ternhdl.spice.*;
import org.ternhdl.stdlib.Benchmarks;

/** Place and route, then post-layout SPICE vs event-sim vs our STA vs OpenSTA (not a test). */
public final class AsicCompare {
    public static void main(String[] a) throws Exception {
        DeviceParams p = DeviceParams.nominal();
        TimingLib lib = new TimingLib(Path.of("cells/gt-param-nominal.json"));
        ExactDb db = new ExactDb(ExactDb.gtFull());
        Path root = Path.of(System.getenv().getOrDefault("TERN_WORK", "/tmp"), "asic");
        List<String> names = a.length > 0 ? List.of(a) : List.of("ripple9", "csel9", "prefix9", "satadd9", "alu9", "fifo3x9", "regfile9x9", "counter9", "mul9");
        ExecutorService ex = Executors.newFixedThreadPool(5);
        Map<String, Future<String[]>> fs = new LinkedHashMap<>();
        for (String nm : names) fs.put(nm, ex.submit(() -> {
            Netlist nl = Mapper.mapToFixpoint(Lower.lower(Benchmarks.all().get(nm).get()).netlist().sweep(), db);
            AsicFlow.Result r = AsicFlow.run(nm, nl, lib, p, root, 7200);
            if (r.error() != null) return new String[]{"| " + nm + " | FAILED |", ""};
            String pnr = String.format(Locale.ROOT, "| %s | %d | %d | %.1f | %.1f%% | %.1f | %d | %.2f | %.1f |", nm, r.cells(), nl.dffCount(), r.areaUm2(), r.util(), r.wirelengthUm(), r.drc(), r.vmidUm(), r.openStaPs());
            String cmp = nl.dffCount() == 0 ? SpiceCompare.compare(nm, "postlayout-", nl, p, lib, r.netCapF(), r.openStaPs()) : "";
            return new String[]{pnr, cmp};
        }));
        StringBuilder sb = new StringBuilder("# OpenROAD results\n\n## Place and route (abstract GT cells on nangate45 tech LEF)\n\n");
        sb.append("| Design | cells | DFF | area (um2) | util | wire (um) | DRC | VMID net cap (fF) | OpenSTA arrival (ps) |\n|---|---|---|---|---|---|---|---|---|\n");
        StringBuilder cmp = new StringBuilder("\n## Post-layout timing: SPICE (with extracted wire C) vs event-sim vs our STA vs OpenSTA\n\n");
        cmp.append("| Design | cells | depth | vectors | SPICE settle mean/max (ps) | event-sim settle mean/max (ps) | mean abs err (ps) | corr | our STA (ps) | STA >= SPICE max | extra transitions SPICE / event-sim | digit transition-count agreement | OpenSTA (ps) |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (var f : fs.entrySet()) {
            String[] r;
            try { r = f.getValue().get(); } catch (ExecutionException e) { r = new String[]{"| " + f.getKey() + " | ERROR " + e.getCause() + " |", ""}; }
            sb.append(r[0]).append('\n');
            if (!r[1].isEmpty()) cmp.append(r[1]).append('\n');
            System.out.println(r[0]); if (!r[1].isEmpty()) System.out.println(r[1]);
        }
        ex.shutdown();
        Files.writeString(Path.of("bench/results/asic.txt"), sb.append(cmp).toString());
    }
}
