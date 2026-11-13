package org.ternhdl.spec;

import static org.junit.jupiter.api.Assertions.*;
import static org.ternhdl.spec.Completeness.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CompletenessTest {
    private static final int ALL = 19683;

    @Test
    void webbIsSingleGateComplete() {
        assertEquals(ALL, closure(List.of(WEBB), false));
        assertTrue(slupecki(List.of(WEBB), false));
    }

    @Test
    void kleeneBasisIsNotComplete() {
        for (boolean k : new boolean[]{false, true}) {
            assertTrue(closure(List.of(MIN, MAX, STI), k) < ALL);
            assertFalse(slupecki(List.of(MIN, MAX, STI), k));
        }
    }

    @Test
    void fastCriterionAgreesWithBruteForce() {
        List<List<Gate>> bases = List.of(
            List.of(MIN, MAX, ICI), List.of(MIN, MAX, STI, PTI, NTI), List.of(ANY, CONS, STI),
            List.of(ANY, SUM, STI), List.of(SUM, MIN, MAX, STI), List.of(SUM), List.of(ANY, CONS));
        for (List<Gate> b : bases) for (boolean k : new boolean[]{false, true})
            assertEquals(closure(b, k) == ALL, slupecki(b, k), b.stream().map(Gate::name).toList() + " const=" + k);
    }

    @Test
    void fullUnaryLibraryGeneratesAll27Unary() {
        assertEquals(27, unaryClosure(LIBRARY, false).cardinality());
    }
}
