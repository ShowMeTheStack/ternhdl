package org.ternhdl.stdlib;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.sim.*;

class StdlibTest {
    static Logic3[] rnd(Random r, int n) { Logic3[] v = new Logic3[n]; for (int i = 0; i < n; i++) v[i] = Logic3.ofBal(r.nextInt(3) - 1); return v; }
    static BigInteger val(Logic3[] v) { return org.ternhdl.core.Arith.toBig(v); }

    @Test
    void addersAgreeWithIntegerArithmetic() {
        Random r = new Random(3);
        for (String name : List.of("ripple9", "csel9", "prefix9", "ripple27", "csel27", "prefix27")) {
            Design d = Benchmarks.all().get(name).get();
            Interpreter it = new Interpreter(d);
            int n = d.node(d.inputs().get("a")).type().width();
            for (int t = 0; t < 500; t++) {
                Logic3[] a = rnd(r, n), b = rnd(r, n), c = rnd(r, 1);
                it.poke("a", a); it.poke("b", b); it.poke("cin", c);
                assertEquals(val(a).add(val(b)).add(val(c)), val(it.peekVec("s")), name);
            }
        }
    }

    @Test
    void multiplierDotSatAndTritsum() {
        Random r = new Random(4);
        Interpreter m = new Interpreter(Benchmarks.all().get("mul9").get());
        Interpreter s = new Interpreter(Benchmarks.all().get("satadd9").get());
        Interpreter dt = new Interpreter(Benchmarks.all().get("dot4x9").get());
        Interpreter ts = new Interpreter(Benchmarks.all().get("tritsum27").get());
        BigInteger h9 = BigInteger.valueOf((19683 - 1) / 2);
        for (int t = 0; t < 500; t++) {
            Logic3[] a = rnd(r, 9), b = rnd(r, 9);
            m.poke("a", a); m.poke("b", b);
            assertEquals(val(a).multiply(val(b)), val(m.peekVec("p")));
            s.poke("a", a); s.poke("b", b);
            BigInteger sum = val(a).add(val(b)).min(h9).max(h9.negate());
            assertEquals(sum, val(s.peekVec("s")));
            BigInteger exp = BigInteger.ZERO;
            for (int i = 0; i < 4; i++) {
                Logic3[] x = rnd(r, 9), w = rnd(r, 1);
                dt.poke("x" + i, x); dt.poke("w" + i, w);
                exp = exp.add(val(x).multiply(val(w)));
            }
            assertEquals(exp, val(dt.peekVec("y")));
            Logic3[] v = rnd(r, 27);
            ts.poke("v", v);
            int e = 0; for (Logic3 q : v) e += q.bal();
            assertEquals(BigInteger.valueOf(e), val(ts.peekVec("s")));
        }
    }

    @Test
    void sequentialBenchmarksBehave() {
        // FIFO: push 3 values, pop them in order
        Interpreter f = new Interpreter(Benchmarks.all().get("fifo3x9").get());
        f.poke("rst", "+"); f.poke("push", "0"); f.poke("pop", "0"); f.poke("din", "000000000"); f.step();
        f.poke("rst", "0");
        String[] vals = {"00000000+", "0000000+-", "000000+00"};
        for (String v : vals) { f.poke("din", v); f.poke("push", "+"); f.step(); }
        f.poke("push", "0");
        assertEquals("+", f.peek("full"));
        for (String v : vals) { assertEquals(v, f.peek("dout")); f.poke("pop", "+"); f.step(); }
        assertEquals("+", f.peek("empty"));
        // multiplier FSM: 9 steps after start
        Interpreter mf = new Interpreter(Benchmarks.all().get("mulfsm9").get());
        mf.poke("rst", "+"); mf.poke("start", "0"); mf.poke("a", "000000000"); mf.poke("b", "000000000"); mf.step();
        mf.poke("rst", "0"); mf.poke("a", "0000+-0+-"); mf.poke("b", "000-+0+0-"); mf.poke("start", "+"); mf.step();
        mf.poke("start", "0");
        for (int i = 0; i < 9; i++) mf.step();
        assertEquals("+", mf.peek("done"));
        BigInteger ea = val(Logic3.parseVec("0000+-0+-")), eb = val(Logic3.parseVec("000-+0+0-"));
        assertEquals(ea.multiply(eb), val(mf.peekVec("p")));
        // register file: write r(+,-) then read
        Interpreter rf = new Interpreter(Benchmarks.all().get("regfile9x9").get());
        rf.poke("rst", "+"); rf.poke("we", "0"); rf.poke("wa", "00"); rf.poke("wd", "000000000"); rf.poke("ra", "00"); rf.poke("rb", "00"); rf.step();
        rf.poke("rst", "0"); rf.poke("we", "+"); rf.poke("wa", "+-"); rf.poke("wd", "+-0+-0+-0"); rf.step();
        rf.poke("we", "0"); rf.poke("ra", "+-");
        assertEquals("+-0+-0+-0", rf.peek("qa"));
        assertEquals("000000000", rf.peek("qb"));
    }
}
