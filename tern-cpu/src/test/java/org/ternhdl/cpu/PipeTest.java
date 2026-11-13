package org.ternhdl.cpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.isa.*;
import org.ternhdl.sim.*;

class PipeTest {
    @Test
    void pipelineMatchesIssOnAllPrograms() throws Exception {
        for (String name : List.of("alu", "fib", "gcd", "sort", "dot", "sieve", "fibrec")) {
            Asm.Program p = CoreTest.prog(name);
            Design core = T27Pipe.build(7);
            T27Pipe.load(core, p);
            List<Lockstep.Event> tr = Lockstep.issTrace(p, 1_000_000);
            SlicedSim sim = new SlicedSim(core);
            Lockstep.Result r = Lockstep.run(core, sim, n -> sim.peekVec(n, 0), tr, 3_000_000);
            System.out.printf("CORE pipe %s: %d instructions, %d cycles (CPI %.2f), %.1fs, nodes=%d, errors=%s%n", name, r.instructions(), r.cycles(),
                (double) r.cycles() / Math.max(1, r.instructions()), r.seconds(), core.nodes().size(), r.errors());
            assertTrue(r.errors().isEmpty(), name + ": " + r.errors());
        }
    }
}
