package org.ternhdl.encode;

import java.util.*;
import org.ternhdl.core.*;
import org.ternhdl.sim.*;

/** Drives the native Interpreter and the Verilator twin with the same stimulus and checks refinement. */
public final class TwinCheck {
    private TwinCheck() { }

    /** Number of digit comparisons where the native value was a level (reported in the fuzz statistics). */
    public static final java.util.concurrent.atomic.AtomicLong COMPARED = new java.util.concurrent.atomic.AtomicLong();

    /** Stimulus: per cycle, per input, the native value (may be X/U). */
    public static List<Map<String, Logic3[]>> randomStimulus(Design d, Random r, int cycles) {
        List<Map<String, Logic3[]>> st = new ArrayList<>();
        for (int c = 0; c < cycles; c++) {
            Map<String, Logic3[]> m = new LinkedHashMap<>();
            for (var e : d.inputs().entrySet()) {
                Type t = d.node(e.getValue()).type();
                Logic3[] v = new Logic3[t.width()];
                for (int i = 0; i < v.length; i++)
                    v[i] = e.getKey().equals("rst") && c > 0 ? (r.nextInt(6) == 0 ? Logic3.P : Logic3.O) : RandomDesign.randValue(r, t.isBit());
                m.put(e.getKey(), v);
            }
            st.add(m);
        }
        return st;
    }

    /** Runs both and returns a list of mismatch descriptions (empty when the twin refines the native design). */
    public static List<String> check(Design d, TwinRunner tw, List<Map<String, Logic3[]>> stim, Random r) throws Exception {
        Interpreter it = new Interpreter(d);
        List<String> lines = new ArrayList<>();
        List<Map<Integer, Logic3[]>> expected = new ArrayList<>();
        for (Map<String, Logic3[]> cyc : stim) {
            StringBuilder sb = new StringBuilder();
            for (String in : tw.inputs()) {
                Logic3[] nat = cyc.get(in);
                it.poke(in, nat);
                Logic3[] twin = nat.clone();
                boolean bit = d.node(d.inputs().get(in)).type().isBit();
                for (int i = 0; i < twin.length; i++)
                    if (!twin[i].isLevel()) twin[i] = bit ? (r.nextBoolean() ? Logic3.P : Logic3.O) : Logic3.ofBal(r.nextInt(3) - 1);
                sb.append(Long.toHexString(tw.pack(in, twin))).append(' ');
            }
            if (tw.inputs().isEmpty()) sb.append("0");
            lines.add(sb.toString().trim());
            Map<Integer, Logic3[]> exp = new HashMap<>();
            for (String o : tw.outputs()) { int id = idOf(d, o); exp.put(id, it.nodeValue(id)); }
            expected.add(exp);
            it.step();
        }
        TwinRunner.Result res = tw.runRaw(lines);
        List<String> bad = new ArrayList<>();
        if (res.exit() != 0) bad.add("twin exit " + res.exit() + ": " + res.stderr());
        if (res.lines().size() != stim.size()) { bad.add("twin printed " + res.lines().size() + " lines for " + stim.size() + " cycles; stderr: " + res.stderr()); return bad; }
        for (int c = 0; c < stim.size(); c++) {
            String[] vals = res.lines().get(c).trim().split(" ");
            for (int k = 0; k < tw.outputs().size(); k++) {
                String port = tw.outputs().get(k);
                Logic3[] twin = tw.unpack(port, Long.parseUnsignedLong(vals[k], 16));
                Logic3[] nat = expected.get(c).get(idOf(d, port));
                for (int i = 0; i < nat.length; i++) {
                    if (twin[i] == null) bad.add("cycle " + c + " " + port + " digit " + i + ": illegal code");
                    if (nat[i].isLevel()) COMPARED.incrementAndGet();
                    if (twin[i] != null && nat[i].isLevel() && nat[i] != twin[i])
                        bad.add("cycle " + c + " " + port + " (" + d.node(idOf(d, port)).op() + ") digit " + i + ": native " + nat[i] + " twin " + twin[i]);
                }
            }
        }
        return bad;
    }

    public static int idOf(Design d, String port) {
        Integer id = d.outputs().get(port);
        return id != null ? id : Integer.parseInt(port.substring(4));
    }
}
