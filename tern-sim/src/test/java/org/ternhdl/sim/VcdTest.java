package org.ternhdl.sim;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;

class VcdTest {
    @Test
    void counterWaveformIsValidVcdAndGtkwaveCanConvertIt() throws Exception {
        Design d = new Design("counter");
        d.resetInput();
        Sig en = d.input("en", Type.BIT);
        Sig c = d.reg("count", Type.trits(4)).named("count_q");
        c.next(d.mux(en, c.add(d.trits("000+")), c));
        d.output("q", c);
        d.output("hi", c.slice(3, 2));
        Path dir = Files.createDirectories(Path.of("build", "waves"));
        Path vcd = dir.resolve("counter.vcd");
        Interpreter it = new Interpreter(d);
        it.trace(vcd);
        it.poke("rst", "+"); it.poke("en", "0"); it.step();
        it.poke("rst", "0"); it.poke("en", "+");
        for (int i = 0; i < 6; i++) it.step();
        it.poke("en", "X"); it.step();
        it.close();
        String text = Files.readString(vcd);
        assertTrue(text.contains("$enddefinitions $end"));
        assertTrue(text.contains("$var string 1"));
        assertTrue(text.contains("s0+-- "), "count after 5 enabled cycles");   // 0*27 + 1*9 + (-1)*3 + (-1) = 5
        assertTrue(text.contains("s0+-X "), "unknown enable: digit-wise exact mux leaves only the differing digit unknown");
        Path fst = dir.resolve("counter.fst");
        assumeTrue(new ProcessBuilder("sh", "-c", "command -v vcd2fst").start().waitFor() == 0, "vcd2fst not installed");
        Process p = new ProcessBuilder("vcd2fst", vcd.toString(), fst.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
        assertTrue(Files.size(fst) > 100, out);
    }
}
