package org.ternhdl.cpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.isa.*;

/** The core's encoded twin under Verilator, lockstep against the ISS (second of the three targets). */
class CoreTwinTest {
    @Test
    void verilatorTwinMatchesIss() throws Exception { runAll(false); }

    @Test
    void verilatorTwinOfPipelineMatchesIss() throws Exception { runAll(true); }

    @Test
    void verilatorTwinOfOooMatchesIss() throws Exception { runAll2("ooo"); }

    void runAll(boolean pipe) throws Exception { runAll2(pipe ? "pipe" : "sc"); }

    void runAll2(String kind) throws Exception {
        boolean pipe = kind.equals("pipe");
        for (String name : List.of("alu", "fib", "gcd", "sort", "dot", "sieve", "fibrec")) {
            Asm.Program p = CoreTest.prog(name);
            List<Lockstep.Event> tr = Lockstep.issTrace(p, 1_000_000);
            Design core = kind.equals("ooo") ? T27Ooo.build(7) : pipe ? T27Pipe.build(7) : T27Core.build(7);
            T27Core.load(core, p);
            TwinRunner tw = new TwinRunner(core, new SvEmitter.Options(Encoding.E1, false, false), TwinRunner.workRoot().resolve("core-" + kind + "-" + name));
            long t0 = System.nanoTime();
            tw.build();
            double buildS = (System.nanoTime() - t0) / 1e9;
            int cycles = (int) (tr.size() * 4.0) + 50;
            Logic3[] z7 = new Logic3[7], z27 = new Logic3[27];
            Arrays.fill(z7, Logic3.O); Arrays.fill(z27, Logic3.O);
            List<String> lines = new ArrayList<>();
            for (int c = 0; c < cycles; c++) {
                StringBuilder sb = new StringBuilder();
                for (String in : tw.inputs()) {
                    Logic3[] v = switch (in) { case "rst" -> new Logic3[]{c == 0 ? Logic3.P : Logic3.O}; case "ld_we" -> new Logic3[]{Logic3.O}; case "ld_row", "peek_row" -> z7; default -> z27; };
                    sb.append(Long.toHexString(tw.pack(in, v))).append(' ');
                }
                lines.add(sb.toString().trim());
            }
            t0 = System.nanoTime();
            TwinRunner.Result res = tw.runRaw(lines);
            double runS = (System.nanoTime() - t0) / 1e9;
            assertEquals(0, res.exit(), res.stderr());
            List<Map<String, Logic3[]>> rec = new ArrayList<>();
            for (String l : res.lines()) {
                String[] v = l.trim().split(" ");
                Map<String, Logic3[]> m = new HashMap<>();
                for (int k = 0; k < tw.outputs().size(); k++) m.put(tw.outputs().get(k), tw.unpack(tw.outputs().get(k), Long.parseUnsignedLong(v[k], 16)));
                rec.add(m);
            }
            Lockstep.Result r = Lockstep.check(rec, tr);
            System.out.printf("CORE-TWIN " + kind + " %s: %d instructions over %d cycles, build %.1fs, run %.2fs, errors=%s%n", name, r.instructions(), r.cycles(), buildS, runS, r.errors());
            assertTrue(r.errors().isEmpty(), name + ": " + r.errors());
        }
    }
}
