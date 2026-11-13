package org.ternhdl.map;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.formal.*;
import org.ternhdl.sim.*;

class MapperTest {
    static final ExactDb DB = new ExactDb(ExactDb.gtFull());

    @Test
    void mappedRandomDesignsSimulateLikeTheRtl() {
        long before = 0, after = 0, cellsBefore = 0, cellsAfter = 0;
        for (int seed = 0; seed < 200; seed++) {
            Random r = new Random(seed);
            Design rtl = RandomDesign.random(r, seed % 2 == 0, 30, 24);
            Netlist low = Lower.lower(rtl).netlist().sweep();      // compare against dead-code-free lowering
            Netlist mapped = Mapper.mapToFixpoint(low, DB);
            before += low.area(); after += mapped.area();
            cellsBefore += low.cellCount(); cellsAfter += mapped.cellCount();
            NetlistDesign.Result nd = NetlistDesign.of(mapped, NetlistDesign.portTypes(rtl));
            Interpreter a = new Interpreter(rtl), b = new Interpreter(nd.design());
            for (int cyc = 0; cyc < 10; cyc++) {
                for (var en : rtl.inputs().entrySet()) {
                    Type t = rtl.node(en.getValue()).type();
                    Logic3[] v = new Logic3[t.width()];
                    for (int i = 0; i < v.length; i++) v[i] = t.isBit() ? (r.nextBoolean() ? Logic3.P : Logic3.O) : Logic3.ofBal(r.nextInt(3) - 1);
                    if (en.getKey().equals("rst")) v[0] = cyc == 0 || r.nextInt(8) == 0 ? Logic3.P : Logic3.O;
                    a.poke(en.getKey(), v); b.poke(en.getKey(), v);
                }
                // cycle 0 is skipped: registers are still U before the reset edge, and resynthesized logic may be more
                // X-pessimistic than the RTL (gate-level X-pessimism). Equivalence on legal values is checked formally.
                if (cyc > 0) for (String o : rtl.outputs().keySet()) {
                    Logic3[] x = a.peekVec(o), y = b.peekVec(o);
                    for (int i = 0; i < x.length; i++)
                        if (x[i].isLevel()) assertEquals(x[i], y[i], "seed " + seed + " cycle " + cyc + " " + o + "[" + i + "]");
                }
                a.step(); b.step();
            }
        }
        System.out.printf("MAPPER random 200 designs (swept lowering vs mapped): area %d -> %d (%.1f%%), cells %d -> %d (%.1f%%)%n", before, after, 100.0 * (before - after) / before, cellsBefore, cellsAfter, 100.0 * (cellsBefore - cellsAfter) / cellsBefore);
    }

    @Test
    void mappedSampleDesignsAreFormallyEquivalent() throws Exception {
        for (Design rtl : List.of(SampleDesigns.allOps(), SampleDesigns.adder(27))) {
            Lower.Result lr = Lower.lower(rtl);
            Netlist mapped = Mapper.mapToFixpoint(lr.netlist(), DB);
            NetlistDesign.Result nd = NetlistDesign.of(mapped, NetlistDesign.portTypes(rtl));
            Sby.Result res = Equivalence.pairedEquivalent(rtl, nd.design(), List.of(), Encoding.E1, Equivalence.DEFAULT_ENGINE, 600, "mapped-" + rtl.name);
            System.out.printf("MAPPEDEQ %s %s %.1fs lowered: %s | mapped: %s%n", rtl.name, res.status(), res.seconds(), lr.netlist().stats(), mapped.stats());
            assertEquals(Sby.Status.PASS, res.status(), res.logTail());
        }
    }
}
