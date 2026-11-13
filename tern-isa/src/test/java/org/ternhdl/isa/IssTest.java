package org.ternhdl.isa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class IssTest {
    static Iss run(String name) throws Exception {
        String src = new String(IssTest.class.getResourceAsStream("/programs/" + name + ".s").readAllBytes(), StandardCharsets.UTF_8);
        Asm.Program p = Asm.assemble(src);
        Iss iss = new Iss(8);
        iss.load(p);
        iss.run(2_000_000);
        System.out.printf("ISS %s: %d instructions, %d words%n", name, iss.steps, p.words().size());
        return iss;
    }

    @Test void fib() throws Exception { assertEquals(832040, run("fib").word(3000)); }
    @Test void fibRecursive() throws Exception { assertEquals(610, run("fibrec").word(3000)); }
    @Test void gcd() throws Exception { assertEquals(21, run("gcd").word(3000)); }
    @Test void sieve() throws Exception { assertEquals(46, run("sieve").word(3000)); }

    @Test void sort() throws Exception {
        Iss s = run("sort");
        long[] exp = {17, -5, 1000000, 0, -123456789, 42, 42, -1, 3812798742493L, -3812798742493L, 7, 2};
        Arrays.sort(exp);
        for (int i = 0; i < exp.length; i++) assertEquals(exp[i], s.word(3000 + 3L * i), "index " + i);
    }

    @Test void dot() throws Exception {
        Iss s = run("dot");
        long[] w = {-1, 0, 1, 0, 1, -1, -1, 0, 1};   // trytes from the three .word lines (low tryte first)
        long[] x = {5, -7, 100, 3, 4, -9, 1000, 22, -1};
        long e = 0; for (int i = 0; i < 9; i++) e += w[i] * x[i];
        assertEquals(e, s.word(3000));
        assertEquals(e, s.word(3027));
    }

    @Test void aluAndBranches() throws Exception {
        Iss s = run("alu");
        long a = Asm.assemble(".word 0t+-0+-0+-0+-0+-0+-0+-0+-0+-").words().get(0L), b = Asm.assemble(".word 0t-0+-0+-0+-0+-0+-0+-0+-0+-0+").words().get(0L);
        long[] exp = {Iss.alu(4, a, b), Iss.alu(5, a, b), Iss.alu(6, a, b), Iss.alu(7, a, b), Iss.alu(8, a, b), Iss.alu(-1, a, 0), Iss.alu(-2, a, 0),
            Iss.alu(-3, a, 0), Iss.alu(-4, a, 0), Iss.alu(-5, a, 0), -a, T27.shl(a, 5), T27.shr(a, 7), Long.signum(a - b), Long.signum(b), T27.mul(a, b)[1],
            T27.shl(b, Iss.shamt(-9)), T27.shr(b, Iss.shamt(-9)), Long.signum(b), 1 + 3 + 9 + 27 + 81 + 243};
        for (int i = 0; i < exp.length; i++) assertEquals(exp[i], s.word(3000 + 3L * i), "slot " + i);
        // independent spot checks of the ternary semantics
        assertEquals(T27.tritwise(a, b, Math::min), exp[0]);
        assertEquals(-a, T27.tritwise(a, 0, (p, q) -> -p));
    }

    @Test void shiftAmountIsUnbalancedLowTrits() {
        assertEquals(0, Iss.shamt(-13));     // ---  -> 0
        assertEquals(13, Iss.shamt(0));      // 000  -> 13
        assertEquals(26, Iss.shamt(13));     // +++  -> 26
        assertEquals(Iss.shamt(4), Iss.shamt(4 + 27));   // only the lowest three trits count
    }

    @Test void wordsAreCentered() {
        Iss s = new Iss(6);
        s.setWord(30, 1 + 2 * T27.MT + 3 * T27.MT * T27.MT);
        assertEquals(1, s.tryte(29)); assertEquals(2, s.tryte(30)); assertEquals(3, s.tryte(31));
    }

    @Test void encodingRoundTrip() {
        for (long v : new long[]{0, 1, -1, 12345, -987654321, T27.H, -T27.H}) assertEquals(v, T27.value(T27.trits(v, 27)));
        assertEquals(-T27.H, T27.wrap(T27.H + 1));
    }
}
