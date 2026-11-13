package org.ternhdl.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LintTest {
    @Test
    void multiWritePortMemoryIsFlagged() {
        Design d = new Design("l");
        Sig we = d.input("we", Type.BIT), a = d.input("a", Type.trits(2)), x = d.input("x", Type.trits(3));
        Design.Mem m = d.memory("m", Type.trits(3), 2);
        m.write(we, a, x);
        assertTrue(d.lint().stream().anyMatch(s -> s.contains("never read")));
        d.output("q", m.read(a));
        assertTrue(d.lint().isEmpty());
        m.write(we, a, x.neg());
        assertTrue(d.lint().get(0).contains("2 write ports"));
    }
}
