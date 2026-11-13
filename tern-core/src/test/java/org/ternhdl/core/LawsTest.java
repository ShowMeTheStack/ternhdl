package org.ternhdl.core;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.Resolve.*;

class LawsTest {
    private static List<Driver> all() {
        List<Driver> l = new ArrayList<>();
        for (Logic3 v : new Logic3[]{Logic3.N, Logic3.O, Logic3.P, Logic3.U, Logic3.X})
            for (Strength s : new Strength[]{Strength.WEAK, Strength.STRONG}) l.add(new Driver(v, s));
        l.add(Driver.NONE);
        return l;
    }

    /** T4: resolution is commutative, associative and idempotent. */
    @Test
    void resolutionLaws() {
        List<Driver> ds = all();
        for (Driver a : ds) {
            assertEquals(a, Resolve.combine(a, a), "idempotent " + a);
            for (Driver b : ds) {
                assertEquals(Resolve.combine(a, b), Resolve.combine(b, a), "commutative " + a + " " + b);
                for (Driver c : ds)
                    assertEquals(Resolve.combine(Resolve.combine(a, b), c), Resolve.combine(a, Resolve.combine(b, c)), "associative " + a + " " + b + " " + c);
            }
        }
    }

    @Test
    void resolutionExamples() {
        assertEquals(Logic3.F, Resolve.resolve(List.of()));
        assertEquals(Logic3.O, Resolve.resolve(List.of(new Driver(Logic3.O, Strength.WEAK))));
        assertEquals(Logic3.P, Resolve.resolve(List.of(new Driver(Logic3.O, Strength.WEAK), new Driver(Logic3.P, Strength.STRONG))));
        assertEquals(Logic3.X, Resolve.resolve(List.of(new Driver(Logic3.N, Strength.STRONG), new Driver(Logic3.P, Strength.STRONG))));
        assertEquals(Logic3.X, Resolve.resolve(List.of(new Driver(Logic3.U, Strength.STRONG), new Driver(Logic3.N, Strength.STRONG))));
        assertEquals(Logic3.U, Resolve.resolve(List.of(new Driver(Logic3.U, Strength.STRONG), new Driver(Logic3.U, Strength.STRONG))));
        assertEquals(Logic3.N, Resolve.resolve(List.of(new Driver(Logic3.N, Strength.STRONG), Driver.NONE)));
    }

    /** Spec section 5: relabeling is the (3^n - 1)/2 offset and is bijective on n-trit vectors. */
    @Test
    void balancedUnbalancedConversionIsRelabelingAndBijective() {
        for (int n = 1; n <= 6; n++) {
            int count = (int) Math.pow(3, n);
            BigInteger off = BigInteger.valueOf((count - 1) / 2);
            Set<BigInteger> seen = new HashSet<>();
            for (int i = 0; i < count; i++) {
                Logic3[] v = Arith.fromBig(BigInteger.valueOf(i - (count - 1) / 2), n);
                BigInteger u = Arith.toUnbalanced(v);
                assertEquals(Arith.toBig(v).add(off), u);
                assertTrue(u.signum() >= 0 && u.compareTo(BigInteger.valueOf(count)) < 0);
                seen.add(u);
            }
            assertEquals(count, seen.size());
        }
    }
}
