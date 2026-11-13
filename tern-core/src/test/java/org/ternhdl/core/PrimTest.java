package org.ternhdl.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.spec.Completeness;
import org.ternhdl.spec.Completeness.Gate;

class PrimTest {
    private static final Map<Prim, Gate> SPEC = Map.ofEntries(
        Map.entry(Prim.STI, Completeness.STI), Map.entry(Prim.PTI, Completeness.PTI), Map.entry(Prim.NTI, Completeness.NTI),
        Map.entry(Prim.ICI, Completeness.ICI), Map.entry(Prim.DCI, Completeness.DCI), Map.entry(Prim.ISZERO, Completeness.ISZERO),
        Map.entry(Prim.MIN, Completeness.MIN), Map.entry(Prim.MAX, Completeness.MAX), Map.entry(Prim.ANY, Completeness.ANY),
        Map.entry(Prim.CONS, Completeness.CONS), Map.entry(Prim.SUM, Completeness.SUM), Map.entry(Prim.MUX3, Completeness.MUX));

    @Test
    void levelSemanticsMatchTheSpecModule() {
        for (var e : SPEC.entrySet()) {
            Prim p = e.getKey();
            Gate g = e.getValue();
            int total = (int) Math.pow(3, p.arity);
            for (int c = 0; c < total; c++) {
                int[] idx = new int[p.arity];
                int m = c;
                for (int k = p.arity - 1; k >= 0; k--) { idx[k] = m % 3; m /= 3; }
                int[] bal = new int[p.arity];
                for (int k = 0; k < p.arity; k++) bal[k] = idx[k] - 1;
                assertEquals(g.eval(idx) - 1, p.level(bal), p + " " + Arrays.toString(idx));
            }
        }
    }

    @Test
    void exactExtensionExamplesFromTheSpec() {
        assertEquals(Logic3.N, Prim.MIN.eval(Logic3.N, Logic3.X));
        assertEquals(Logic3.P, Prim.MAX.eval(Logic3.P, Logic3.X));
        assertEquals(Logic3.X, Prim.CONS.eval(Logic3.N, Logic3.X));
        assertEquals(Logic3.O, Prim.CONS.eval(Logic3.O, Logic3.X));
        assertEquals(Logic3.X, Prim.ANY.eval(Logic3.P, Logic3.X));
        assertEquals(Logic3.X, Prim.SUM.eval(Logic3.O, Logic3.X));
        assertEquals(Logic3.P, Prim.MUX3.eval(Logic3.X, Logic3.P, Logic3.P, Logic3.P));
        assertEquals(Logic3.X, Prim.MUX3.eval(Logic3.X, Logic3.P, Logic3.O, Logic3.P));
        assertEquals(Logic3.N, Prim.MUX3.eval(Logic3.N, Logic3.N, Logic3.X, Logic3.X));   // selected input known
        assertEquals(Logic3.X, Prim.STI.eval(Logic3.U));
        assertEquals(Logic3.X, Prim.BUF.eval(Logic3.F));
    }

    /** T5: a returned level must be produced by every completion; X means two completions disagree. */
    @Test
    void exactExtensionIsSound() {
        Logic3[] all = Logic3.values();
        for (Prim p : Prim.values()) {
            if (p.arity == 0 || p.arity > 2) continue;
            int total = (int) Math.pow(all.length, p.arity);
            for (int c = 0; c < total; c++) {
                Logic3[] in = new Logic3[p.arity];
                int m = c;
                for (int k = 0; k < p.arity; k++) { in[k] = all[m % all.length]; m /= all.length; }
                Logic3 r = p.eval(in);
                Set<Integer> outs = new HashSet<>();
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                    int[] v = {a, b};
                    boolean ok = true;
                    for (int k = 0; k < p.arity; k++) if (in[k].isLevel() && in[k].bal() != v[k]) ok = false;
                    if (!ok) continue;
                    if (p.arity == 1 && b != -1) continue;
                    outs.add(p.level(Arrays.copyOf(v, p.arity)));
                }
                if (r.isLevel()) assertEquals(Set.of(r.bal()), outs, p + " " + Arrays.toString(in));
                else assertTrue(outs.size() > 1, p + " " + Arrays.toString(in));
            }
        }
    }

    @Test
    void arithmeticMatchesBigInteger() {
        for (int n = 1; n <= 4; n++) {
            int count = (int) Math.pow(3, n);
            for (int i = 0; i < count; i++) for (int j = 0; j < count; j++) for (int c = -1; c <= 1; c++) {
                Logic3[] a = Arith.fromBig(java.math.BigInteger.valueOf(i - (count - 1) / 2), n);
                Logic3[] b = Arith.fromBig(java.math.BigInteger.valueOf(j - (count - 1) / 2), n);
                Logic3[] s = Arith.add(a, b, Logic3.ofBal(c));
                var exp = Arith.toBig(a).add(Arith.toBig(b)).add(java.math.BigInteger.valueOf(c));
                assertEquals(Arith.toBig(Arith.fromBig(exp, n)), Arith.toBig(s));
                assertEquals(Integer.signum(Arith.toBig(a).compareTo(Arith.toBig(b))), Arith.cmp(a, b).bal());
            }
        }
    }

    @Test
    void unknownDigitPoisonsHigherSumDigitsOnly() {
        Logic3[] a = Logic3.parseVec("+0X-");   // d3=+ d2=0 d1=X d0=-
        Logic3[] b = Logic3.parseVec("0+0+");
        Logic3[] s = Arith.add(a, b, Logic3.O);
        assertEquals("XXX0", Logic3.str(s));     // digit 0: - + + = 0 ; digits 1.. unknown
    }

    @Test
    void shiftRightRoundsToNearestWithNoTies() {
        for (int n = 2; n <= 6; n++) {
            int count = (int) Math.pow(3, n);
            for (int i = 0; i < count; i++) {
                var v = java.math.BigInteger.valueOf(i - (count - 1) / 2);
                var shifted = Arith.toBig(Arith.shr(Arith.fromBig(v, n), 1));
                // nearest multiple of 3 to v, divided by 3: |v - 3*shifted| <= 1 always, and unique
                assertTrue(v.subtract(shifted.multiply(java.math.BigInteger.valueOf(3))).abs().intValue() <= 1);
            }
        }
    }
}
