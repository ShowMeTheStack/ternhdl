package org.ternhdl.map;

import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.formal.*;
import org.ternhdl.stdlib.Benchmarks;

/** Mapping equivalence for one benchmark: args name engine timeoutSec (not a test). */
public final class MappingOne {
    public static void main(String[] a) throws Exception {
        Design rtl = Benchmarks.all().get(a[0]).get();
        Lower.Result lr = Lower.lower(rtl);
        Netlist ours = Mapper.mapToFixpoint(lr.netlist().sweep(), new ExactDb(ExactDb.gtFull()));
        NetlistDesign.Result nd = NetlistDesign.of(ours, NetlistDesign.portTypes(rtl));
        Sby.Result r = Equivalence.pairedEquivalent(rtl, nd.design(), MappingBench.pairs(rtl, lr, nd), Encoding.E1, a[1], Integer.parseInt(a[2]), "mapone-" + a[0] + "-" + a[1].replace(' ', '_'));
        System.out.printf("MAPPING_ONE %s engine=%s %s %.1fs%n", a[0], a[1], r.status(), r.seconds());
    }
}
