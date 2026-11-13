package org.ternhdl.formal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;

class FormalTest {
    private static void pass(Sby.Result r, String what) {
        System.out.printf("FORMAL %s %s %.1fs%n", what, r.status(), r.seconds());
        assertEquals(Sby.Status.PASS, r.status(), what + "\n" + r.logTail());
    }

    @Test
    void allOpsTwinEqualsReferenceUnderEachEncoding() throws Exception {
        for (Encoding enc : Encoding.values())
            pass(Equivalence.twinRefinesReference(SampleDesigns.allOps(), enc, Equivalence.DEFAULT_ENGINE, 600, "allops-" + enc), "allops " + enc);
    }

    @Test
    void addersTwinEqualsDigitSerialReference() throws Exception {
        for (int n : new int[]{1, 3, 9, 27})
            pass(Equivalence.twinRefinesReference(SampleDesigns.adder(n), Encoding.E1, Equivalence.DEFAULT_ENGINE, 600, "adder" + n), "adder" + n + " E1 digit-serial");
    }

    @Test
    void prefixAdderTwinEqualsReference() throws Exception {
        for (int n : new int[]{4, 9, 27})
            for (Encoding e : Encoding.values())
                pass(Equivalence.twinRefinesReference(SampleDesigns.adder(n), e, RefEmitter.Arith.DIGIT_SERIAL, SvEmitter.AdderStyle.PREFIX, Equivalence.DEFAULT_ENGINE, 900, "pfx-adder" + n + "-" + e), "prefix adder" + n + " " + e);
    }

    /** Cross-check: the digit rule computes numeric balanced addition (whole-vector integer reference), small widths. */
    @Test
    void smallAddersTwinEqualsValueReference() throws Exception {
        for (int n : new int[]{1, 2, 3, 4})
            pass(Equivalence.twinRefinesReference(SampleDesigns.adder(n), Encoding.E1, RefEmitter.Arith.VALUE, "z3", 600, "adderv" + n), "adder" + n + " E1 value");
    }

    @Test
    void sequentialCounterTwinEqualsReferenceByInduction() throws Exception {
        Design d = new Design("cnt");
        d.resetInput();
        Sig en = d.input("en", Type.BIT);
        Sig c = d.reg("c", Type.trits(4));
        c.next(d.mux(en, c.add(d.trits("000+")), c));
        d.output("q", c);
        pass(Equivalence.twinRefinesReference(d, Encoding.E1, Equivalence.DEFAULT_ENGINE, 600, "counter"), "counter E1 pdr");
        pass(Equivalence.twinRefinesReference(d, Encoding.E1, "z3", 600, "counter-z3"), "counter E1 z3");
    }

    /** Guards against vacuous assumptions in the reference adder: a subtractor twin must not prove equal to an adder reference. */
    @Test
    void adderReferenceIsNotVacuous() throws Exception {
        Design add = new Design("addref"), sub = new Design("subtw");
        for (Design d : new Design[]{add, sub}) {
            Sig x = d.input("x", Type.trits(3)), y = d.input("y", Type.trits(3));
            d.output("o", d == add ? x.add(y) : x.add(y.neg()));
        }
        Sby.Result r = Equivalence.twinVsReference(sub, add, Encoding.E1, "z3", 300, "neg-addsub");
        System.out.printf("FORMAL vacuity-check %s %.1fs%n", r.status(), r.seconds());
        assertEquals(Sby.Status.FAIL, r.status(), r.logTail());
        pass(Equivalence.twinVsReference(add, add, Encoding.E1, "z3", 300, "pos-addadd"), "adder twin vs adder ref (outputs)");
    }

    @Test
    void differentDesignsAreReportedNotEquivalent() throws Exception {
        Design a = new Design("dmin"), b = new Design("dmax");
        for (Design d : new Design[]{a, b}) {
            Sig x = d.input("x", Type.trits(2)), y = d.input("y", Type.trits(2));
            d.output("o", d == a ? x.min(y) : x.max(y));
        }
        Sby.Result r = Equivalence.twinsEquivalent(a, b, Encoding.E1, "z3", 300, "neg-minmax");
        System.out.printf("FORMAL negative %s %.1fs%n", r.status(), r.seconds());
        assertEquals(Sby.Status.FAIL, r.status(), r.logTail());
    }
}
