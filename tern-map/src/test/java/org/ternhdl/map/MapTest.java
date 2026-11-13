package org.ternhdl.map;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.formal.*;
import org.ternhdl.sim.*;

class MapTest {
    @Test
    void everyUnaryFunctionHasACircuit() {
        int[] hist = new int[10];
        for (int c = 0; c < 27; c++) {
            assertNotEquals(Integer.MAX_VALUE, UnaryDb.cost(c), "code " + c);
            hist[Math.min(9, UnaryDb.cost(c))]++;
        }
        System.out.println("UNARYDB cost histogram (index = area): " + Arrays.toString(hist));
    }

    static List<int[]> pairs(Design rtl, Lower.Result lr, NetlistDesign.Result nd) {
        List<int[]> p = new ArrayList<>();
        for (int[] rd : lr.regDigits()) {
            int k = 0, dffOrdinal = -1;
            for (int i = 0; i <= rd[2]; i++) if (lr.netlist().insts().get(i).cell == Cell.DFF) dffOrdinal = k++;
            p.add(new int[]{rtl.regs().get(rd[0]).readNode(), rd[1], nd.dffRegNode()[dffOrdinal], 0});
        }
        return p;
    }

    /** Lowered netlist, converted back to a Design, simulates like the RTL from reset with legal inputs. */
    @Test
    void loweredRandomDesignsSimulateLikeTheRtl() {
        for (int seed = 0; seed < 300; seed++) {
            Random r = new Random(seed);
            Design rtl = RandomDesign.random(r, seed % 2 == 0, 30, 24);
            Lower.Result lr = Lower.lower(rtl);
            NetlistDesign.Result nd = NetlistDesign.of(lr.netlist(), NetlistDesign.portTypes(rtl));
            Interpreter a = new Interpreter(rtl), b = new Interpreter(nd.design());
            for (int cyc = 0; cyc < 10; cyc++) {
                for (var en : rtl.inputs().entrySet()) {
                    Type t = rtl.node(en.getValue()).type();
                    Logic3[] v = new Logic3[t.width()];
                    for (int i = 0; i < v.length; i++) v[i] = t.isBit() ? (r.nextBoolean() ? Logic3.P : Logic3.O) : Logic3.ofBal(r.nextInt(3) - 1);
                    if (en.getKey().equals("rst")) v[0] = cyc == 0 || r.nextInt(8) == 0 ? Logic3.P : Logic3.O;
                    a.poke(en.getKey(), v); b.poke(en.getKey(), v);
                }
                for (String o : rtl.outputs().keySet()) {
                    Logic3[] x = a.peekVec(o), y = b.peekVec(o);
                    for (int i = 0; i < x.length; i++)
                        if (x[i].isLevel()) assertEquals(x[i], y[i], "seed " + seed + " cycle " + cyc + " " + o + "[" + i + "]");
                }
                a.step(); b.step();
            }
        }
    }

    @Test
    void loweredSampleDesignsAreFormallyEquivalent() throws Exception {
        Design counter = new Design("cnt");
        counter.resetInput();
        Sig en = counter.input("en", Type.BIT);
        Sig c = counter.reg("c", Type.trits(4));
        c.next(counter.mux(en, c.add(counter.trits("000+")), c));
        counter.output("q", c);
        for (Design rtl : List.of(SampleDesigns.allOps(), SampleDesigns.adder(27), counter)) {
            Lower.Result lr = Lower.lower(rtl);
            NetlistDesign.Result nd = NetlistDesign.of(lr.netlist(), NetlistDesign.portTypes(rtl));
            Sby.Result res = Equivalence.pairedEquivalent(rtl, nd.design(), pairs(rtl, lr, nd), Encoding.E1, Equivalence.DEFAULT_ENGINE, 600, "map-" + rtl.name);
            System.out.printf("MAPEQ %s %s %.1fs lowered: %s%n", rtl.name, res.status(), res.seconds(), lr.netlist().stats());
            assertEquals(Sby.Status.PASS, res.status(), res.logTail());
        }
    }
}
