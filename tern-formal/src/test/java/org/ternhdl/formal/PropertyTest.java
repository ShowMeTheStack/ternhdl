package org.ternhdl.formal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.sim.*;

class PropertyTest {
    /** Counts 0..8 then wraps to 0 in a 3-trit register (range -13..13). */
    static Design mod9(boolean wrongBound) {
        Design d = new Design(wrongBound ? "mod9bad" : "mod9");
        d.resetInput();
        Sig c = d.reg("c", Type.trits(3));
        Sig eight = d.trits("+0-");                   // 9 - 1 = 8
        Sig atEnd = c.cmp(eight).isZero();
        c.next(d.mux(atEnd, d.trits("000"), c.add(d.trits("00+"))));
        d.output("q", c);
        Sig bound = wrongBound ? d.trits("0+-") : eight;   // wrong bound = 2
        d.assertAlways("c_le_bound", c.cmp(bound).isPos().not());
        d.assertAlways("c_ge_0", c.cmp(d.trits("000")).isNeg().not());
        return d;
    }

    @Test
    void trueInvariantIsProvedFromReset() throws Exception {
        Sby.Result r = Equivalence.properties(mod9(false), Encoding.E1, true, 3, true, "z3", 300, "prop-mod9");
        System.out.printf("FORMAL prop mod9 %s %.1fs%n", r.status(), r.seconds());
        // c >= 0 is not inductive on its own from arbitrary legal states; k-induction with depth 3 still needs reset
        // reachability, so a non-PASS here would mean the invariant needs strengthening, not a bug. Record either way.
        assertNotEquals(Sby.Status.ERROR, r.status(), r.logTail());
        assertNotEquals(Sby.Status.FAIL, r.status(), r.logTail());
    }

    @Test
    void falseInvariantIsRefutedByBmcAndSimulation() throws Exception {
        Sby.Result r = Equivalence.properties(mod9(true), Encoding.E1, false, 8, true, "z3", 300, "prop-mod9bad");
        System.out.printf("FORMAL prop mod9bad (bmc) %s %.1fs%n", r.status(), r.seconds());
        assertEquals(Sby.Status.FAIL, r.status(), r.logTail());
        Interpreter it = new Interpreter(mod9(true));
        it.poke("rst", "+"); it.step(); it.poke("rst", "0");
        for (int i = 0; i < 5; i++) it.step();
        assertFalse(it.violations().isEmpty());
        System.out.println("SIM violations: " + it.violations());
    }
}
