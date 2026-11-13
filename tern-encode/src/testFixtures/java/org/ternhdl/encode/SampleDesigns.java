package org.ternhdl.encode;

import org.ternhdl.core.*;

/** Shared sample designs for tests in several modules. */
public final class SampleDesigns {
    private SampleDesigns() { }

    /** One design containing every operation; all legal input combinations; checked mode. */
    public static Design allOps() {
        Design d = new Design("allops");
        Sig a = d.input("a", Type.TRIT), b = d.input("b", Type.TRIT), c = d.input("c", Type.TRIT), s = d.input("s", Type.TRIT);
        Sig x = d.input("x", Type.trits(2)), y = d.input("y", Type.trits(2)), cin = d.input("cin", Type.TRIT);
        Sig bt = d.input("bt", Type.BIT), bu = d.input("bu", Type.BIT);
        d.output("sti", a.neg()); d.output("pti", a.pti()); d.output("nti", a.nti()); d.output("ici", a.ici());
        d.output("dci", a.dci()); d.output("isz", a.isZeroDigit());
        d.output("min", a.min(b)); d.output("max", a.max(b)); d.output("any", a.any(b)); d.output("cons", a.cons(b)); d.output("sum", a.sum(b));
        d.output("mux3", s.mux3(a, b, c));
        d.output("addc", x.add(y, cin)); d.output("add", x.add(y)); d.output("cmp", x.cmp(y));
        d.output("shl", x.shl(1)); d.output("shr", x.shr(1)); d.output("cat", x.concat(a)); d.output("slc", x.slice(1, 1));
        d.output("ispos", a.isPos()); d.output("isneg", a.isNeg()); d.output("iszero", a.isZero());
        d.output("tt", bt.toTrit()); d.output("tts", bt.toTritSigned()); d.output("bn", bt.not());
        d.output("band", bt.and(bu)); d.output("bor", bt.or(bu)); d.output("muxb", d.mux(bt, x, y));
        return d;
    }


    /** n-trit adder with carry-in and three-way compare of the same operands. */
    public static Design adder(int n) {
        Design d = new Design("adder" + n);
        Sig a = d.input("a", Type.trits(n)), b = d.input("b", Type.trits(n)), c = d.input("cin", Type.TRIT);
        d.output("s", a.add(b, c));
        d.output("k", a.cmp(b));
        return d;
    }
}
