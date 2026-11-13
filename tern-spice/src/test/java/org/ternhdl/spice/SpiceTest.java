package org.ternhdl.spice;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.map.*;
import org.ternhdl.sim.*;
import org.ternhdl.stdlib.Benchmarks;

class SpiceTest {
    static final DeviceParams P = DeviceParams.nominal();

    /** Every cell's DC output at every input combination quantizes to its truth-table level. */
    @Test
    void dcTruthTablesOfAllCells() throws Exception {
        for (Cell c : Cell.values()) {
            if (c == Cell.DFF || c.isTie()) continue;
            int k = c.arity, total = (int) Math.pow(3, k);
            StringBuilder b = new StringBuilder("* dc " + c + "\n").append(P.spiceParams()).append(CellModels.subckt(c));
            b.append(String.format(Locale.ROOT, "vdd vdd 0 %g%nvmid vmid 0 %g%nvss vss 0 0%n", P.vdd(), P.vmid()));
            for (int combo = 0; combo < total; combo++) {
                int m = combo;
                int[] bal = new int[k];
                for (int i = k - 1; i >= 0; i--) { bal[i] = m % 3 - 1; m /= 3; }
                b.append("x").append(combo);
                for (int i = 0; i < k; i++) b.append(" ").append(bal[i] == -1 ? "vss" : bal[i] == 0 ? "vmid" : "vdd");
                b.append(" y").append(combo).append(" vdd vmid vss ").append(c.name()).append('\n');
            }
            b.append(".control\nop\n");
            for (int combo = 0; combo < total; combo++) b.append("print v(y").append(combo).append(")\n");
            b.append("quit\n.endc\n.end\n");
            String out = Ngspice.run(Ngspice.workRoot().resolve("dc-" + c), b.toString(), 120);
            for (int combo = 0; combo < total; combo++) {
                int m = combo;
                int[] bal = new int[k];
                for (int i = k - 1; i >= 0; i--) { bal[i] = m % 3 - 1; m /= 3; }
                java.util.regex.Matcher mt = java.util.regex.Pattern.compile("v\\(y" + combo + "\\) = ([-0-9.eE+]+)").matcher(out);
                assertTrue(mt.find(), c + " combo " + combo + "\n" + out);
                double v = Double.parseDouble(mt.group(1));
                assertEquals(Logic3.ofBal(c.level(bal)), SpiceNetlist.quantize(P, v), c + " " + Arrays.toString(bal) + " v=" + v);
                assertEquals(SpiceNetlist.volts(P, Logic3.ofBal(c.level(bal))), v, 0.01, c + " " + Arrays.toString(bal) + " output level accuracy");
            }
        }
    }

    /** A mapped 9-trit adder simulated at the electrical level agrees with the logic simulator at every vector. */
    @Test
    void mappedAdderTransientMatchesLogic() throws Exception {
        Design rtl = Benchmarks.all().get("ripple9").get();
        Netlist nl = Mapper.mapToFixpoint(Lower.lower(rtl).netlist().sweep(), new ExactDb(ExactDb.gtFull()));
        Random r = new Random(11);
        List<Map<String, Logic3[]>> vecs = new ArrayList<>();
        for (int k = 0; k < 40; k++) {
            Map<String, Logic3[]> m = new LinkedHashMap<>();
            for (String in : List.of("a", "b")) { Logic3[] v = new Logic3[9]; for (int i = 0; i < 9; i++) v[i] = Logic3.ofBal(r.nextInt(3) - 1); m.put(in, v); }
            m.put("cin", new Logic3[]{Logic3.ofBal(r.nextInt(3) - 1)});
            vecs.add(m);
        }
        int[] outNets = nl.outputs().get("s");
        List<String> probes = new ArrayList<>();
        for (int n : outNets) probes.add("v(n" + n + ")");
        double period = 2e-9;
        Path dir = Ngspice.workRoot().resolve("adder9-tran");
        Ngspice.run(dir, SpiceNetlist.deck(nl, P, vecs, period, 20e-12, 1e-12, probes), 600);
        double[][] w = Ngspice.read(dir.resolve("out.txt"), probes.size());
        Interpreter it = new Interpreter(rtl);
        int mism = 0;
        for (int k = 0; k < vecs.size(); k++) {
            for (var en : vecs.get(k).entrySet()) it.poke(en.getKey(), en.getValue());
            Logic3[] exp = it.peekVec("s");
            double t = (k + 1) * period - 50e-12;
            double[] row = w[0];
            for (double[] rr : w) if (rr[0] <= t) row = rr;
            for (int i = 0; i < outNets.length; i++) if (SpiceNetlist.quantize(P, row[i + 1]) != exp[i]) mism++;
        }
        System.out.println("SPICE adder9 40 vectors: digit mismatches = " + mism + ", netlist " + nl.stats());
        assertEquals(0, mism);
    }
}
