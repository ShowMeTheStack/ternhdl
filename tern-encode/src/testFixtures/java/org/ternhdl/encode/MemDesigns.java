package org.ternhdl.encode;

import org.ternhdl.core.*;

/** Small designs with memories for tests. */
public final class MemDesigns {
    private MemDesigns() { }

    /** Two write ports (second wins), two async read ports, 2-trit addresses (9 entries), 4-trit words, one init entry. */
    public static Design twoPort() {
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
}
