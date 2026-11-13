package org.ternhdl.map;

import java.util.*;
import org.ternhdl.core.*;

/** Converts a netlist back into a Design (one-trit signals) for simulation and formal equivalence. */
public final class NetlistDesign {
    public record Result(Design design, int[] dffRegNode) { }   // dffRegNode[k] = REG node id of the k-th DFF

    private NetlistDesign() { }

    public static Result of(Netlist nl, Map<String, Type> portTypes) {
        Design d = new Design(nl.name);
        Sig[] s = new Sig[nl.netCount()];
        for (var en : nl.inputs.entrySet()) {
            Type t = portTypes.get(en.getKey());
            Sig in = d.input(en.getKey(), t);   // rst stays a plain input: reset logic is already in the netlist
            if (t.isBit()) s[en.getValue()[0]] = in.toTrit();
            else for (int i = 0; i < t.width(); i++) s[en.getValue()[i]] = in.digit(i);
        }
        List<Integer> dffNodes = new ArrayList<>();
        List<Netlist.Inst> dffs = new ArrayList<>();
        List<Sig> dffSigs = new ArrayList<>();
        int k = 0;
        for (Netlist.Inst i : nl.insts) {
            if (i.cell == Cell.DFF) {
                Sig r = d.reg("q" + k + "_" + nl.dffNames.get(k).replaceAll("[^A-Za-z0-9_]", "_"), Type.TRIT);
                s[i.out] = r;
                dffNodes.add(r.id());
                dffSigs.add(r);
                dffs.add(i);
                k++;
            }
        }
        for (Netlist.Inst i : nl.insts) {
            if (i.cell == Cell.DFF) continue;
            Sig[] a = new Sig[i.in.length];
            for (int j = 0; j < a.length; j++) a[j] = s[i.in[j]];
            s[i.out] = switch (i.cell) {
                case STI -> a[0].neg(); case PTI -> a[0].pti(); case NTI -> a[0].nti(); case ICI -> a[0].ici(); case DCI -> a[0].dci();
                case ISZERO -> a[0].isZeroDigit();
                case MIN -> a[0].min(a[1]); case MAX -> a[0].max(a[1]); case ANY -> a[0].any(a[1]); case CONS -> a[0].cons(a[1]); case SUM -> a[0].sum(a[1]);
                case NMIN -> a[0].min(a[1]).neg(); case NMAX -> a[0].max(a[1]).neg(); case NANY -> a[0].any(a[1]).neg();
                case MUX3 -> a[0].mux3(a[1], a[2], a[3]);
                case TIE_N -> d.trits("-"); case TIE_O -> d.trits("0"); case TIE_P -> d.trits("+");
                case DFF -> throw new IllegalStateException();
            };
        }
        for (int j = 0; j < dffs.size(); j++) {
            dffSigs.get(j).next(s[dffs.get(j).in[0]]);
        }
        for (var en : nl.outputs.entrySet()) {
            Type t = portTypes.get(en.getKey());
            int[] n = en.getValue();
            if (t.isBit()) d.output(en.getKey(), s[n[0]].isPos());
            else {
                Sig v = s[n[n.length - 1]];
                if (n.length > 1) { Sig[] rest = new Sig[n.length - 1]; for (int i = n.length - 2, j = 0; i >= 0; i--, j++) rest[j] = s[n[i]]; v = v.concat(rest); }
                d.output(en.getKey(), v);
            }
        }
        int[] map = new int[dffNodes.size()];
        for (int j = 0; j < map.length; j++) map[j] = dffNodes.get(j);
        return new Result(d, map);
    }

    /** Port types of an RTL design, for use with of(). */
    public static Map<String, Type> portTypes(Design rtl) {
        Map<String, Type> m = new HashMap<>();
        for (var en : rtl.inputs().entrySet()) m.put(en.getKey(), rtl.node(en.getValue()).type());
        for (var en : rtl.outputs().entrySet()) m.put(en.getKey(), rtl.node(en.getValue()).type());
        return m;
    }

}
