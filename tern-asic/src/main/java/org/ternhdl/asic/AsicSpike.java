package org.ternhdl.asic;

import java.nio.file.*;
import java.util.*;
import org.ternhdl.map.*;
import org.ternhdl.spice.*;
import org.ternhdl.stdlib.Benchmarks;

/** Runs the OpenROAD flow for the named benchmarks (not a test). */
public final class AsicSpike {
    public static void main(String[] a) throws Exception {
        DeviceParams p = DeviceParams.nominal();
        TimingLib lib = new TimingLib(Path.of("cells/gt-param-nominal.json"));
        ExactDb db = new ExactDb(ExactDb.gtFull());
        Path root = Path.of(System.getenv().getOrDefault("TERN_WORK", "/tmp"), "asic");
        for (String nm : a) {
            Netlist nl = Mapper.mapToFixpoint(Lower.lower(Benchmarks.all().get(nm).get()).netlist().sweep(), db);
            AsicFlow.Result r = AsicFlow.run(nm, nl, lib, p, root, 3600);
            if (r.error() != null) { System.out.println("ASIC " + nm + " FAILED\n" + r.error()); continue; }
            System.out.printf(Locale.ROOT, "ASIC %s cells=%d area=%.1fum2 util=%.1f%% wire=%.1fum drc=%d openSTA=%.1fps vmidCap=%.2ffF nets_with_cap=%d%n",
                nm, r.cells(), r.areaUm2(), r.util(), r.wirelengthUm(), r.drc(), r.openStaPs(), r.vmidUm(), r.netCapF().size());
        }
    }
}
