package org.ternhdl.cpu;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.isa.*;
import org.ternhdl.sim.*;

class CoreTest {
    static Asm.Program prog(String name) throws Exception {
        return Asm.assemble(new String(CoreTest.class.getResourceAsStream("/" + name + ".s").readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void singleCycleCoreMatchesIssOnAllPrograms() throws Exception {
        for (String name : List.of("alu", "fib", "gcd", "sort", "dot", "sieve", "fibrec")) {
            Asm.Program p = prog(name);
            Design core = T27Core.build(7);
            T27Core.load(core, p);
            List<Lockstep.Event> tr = Lockstep.issTrace(p, 1_000_000);
            SlicedSim sim = new SlicedSim(core);
            Lockstep.Result r = Lockstep.run(core, sim, n -> sim.peekVec(n, 0), tr, 2_000_000);
            System.out.printf("CORE sc %s: %d instructions, %d cycles (CPI %.2f), %.1fs, nodes=%d, errors=%s%n", name, r.instructions(), r.cycles(),
                (double) r.cycles() / Math.max(1, r.instructions()), r.seconds(), core.nodes().size(), r.errors());
            assertTrue(r.errors().isEmpty(), name + ": " + r.errors());
        }
    }
}
