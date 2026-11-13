package org.ternhdl.sim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;

class MemSimTest {
    static Design design() {
        Design d = new Design("mem2p");
        Sig we0 = d.input("we0", Type.BIT), we1 = d.input("we1", Type.BIT);
        Sig wa0 = d.input("wa0", Type.trits(2)), wa1 = d.input("wa1", Type.trits(2));
        Sig wd0 = d.input("wd0", Type.trits(4)), wd1 = d.input("wd1", Type.trits(4));
        Sig ra = d.input("ra", Type.trits(2)), rb = d.input("rb", Type.trits(2));
        Design.Mem m = d.memory("m", Type.trits(4), 2);
        m.init(-4, Logic3.parseVec("+-0+"));
        m.write(we0, wa0, wd0);
        m.write(we1, wa1, wd1);
        d.output("qa", m.read(ra));
        d.output("qb", m.read(rb).add(d.trits("000+")));
        return d;
    }

    @Test
    void initialContentAndWriteOrder() {
        Interpreter it = new Interpreter(design());
        for (String in : List.of("we0", "we1")) it.poke(in, "0");
        it.poke("ra", "--"); it.poke("rb", "00"); it.poke("wa0", "00"); it.poke("wa1", "00"); it.poke("wd0", "000+"); it.poke("wd1", "00+0");
        assertEquals("+-0+", it.peek("qa"));             // init at -4
        assertEquals("000+", it.peek("qb"));             // O + 1
        it.poke("we0", "+"); it.poke("we1", "+"); it.step();
        it.poke("we0", "0"); it.poke("we1", "0"); it.poke("ra", "00");
        assertEquals("00+0", it.peek("qa"));             // second port wins
        it.poke("we0", "+"); it.poke("wa0", "X0"); it.step();   // unknown address poisons every entry
        it.poke("we0", "0"); it.poke("ra", "--");
        assertEquals("XXXX", it.peek("qa"));
    }

    @Test
    void slicedMatchesInterpreterLaneByLane() {
        Design d = design();
        Random r = new Random(5);
        SlicedSim ss = new SlicedSim(d);
        Interpreter[] its = new Interpreter[SlicedSim.LANES];
        for (int l = 0; l < its.length; l++) its[l] = new Interpreter(d);
        for (int cyc = 0; cyc < 40; cyc++) {
            for (var en : d.inputs().entrySet()) {
                Type t = d.node(en.getValue()).type();
                for (int l = 0; l < its.length; l++) {
                    Logic3[] v = new Logic3[t.width()];
                    for (int i = 0; i < v.length; i++) v[i] = RandomDesign.randValue(r, t.isBit());
                    if (r.nextInt(4) != 0) for (int i = 0; i < v.length; i++) if (!v[i].isLevel()) v[i] = t.isBit() ? Logic3.O : Logic3.P;   // mostly legal
                    its[l].poke(en.getKey(), v); ss.poke(en.getKey(), l, v);
                }
            }
            for (Node nd : d.nodes()) for (int l = 0; l < its.length; l++)
                assertArrayEquals(its[l].nodeValue(nd.id()), ss.nodeValue(nd.id(), l), "cycle " + cyc + " node " + nd.id() + " lane " + l);
            for (Interpreter it : its) it.step();
            ss.step();
        }
    }
}
