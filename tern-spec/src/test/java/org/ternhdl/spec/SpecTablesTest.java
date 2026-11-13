package org.ternhdl.spec;

import static org.junit.jupiter.api.Assertions.*;
import static org.ternhdl.spec.Completeness.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The Appendix A truth tables in README.md must match the Java gate definitions. */
class SpecTablesTest {
    private static int idx(String sym) {
        return switch (sym.trim()) { case "−", "-" -> 0; case "0" -> 1; case "+" -> 2; default -> throw new IllegalArgumentException(sym); };
    }

    private static List<String> appendix() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("..", "README.md"));
        int start = 0;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("## Appendix A")) start = i;
        return lines.subList(start, lines.size());
    }

    @Test
    void unaryTablesMatch() throws Exception {
        Map<String, Gate> g = Map.of("STI", STI, "PTI", PTI, "NTI", NTI, "ICI", ICI, "DCI", DCI, "IsZero", ISZERO);
        int checked = 0;
        for (String line : appendix()) {
            String[] c = line.split("\\|");
            if (c.length != 5) continue;
            Gate gate = g.get(c[1].trim());
            if (gate == null) continue;
            for (int x = 0; x < 3; x++) assertEquals(idx(c[2 + x]), gate.eval(x), gate.name() + " input " + x);
            checked++;
        }
        assertEquals(6, checked);
    }

    @Test
    void binaryTablesMatch() throws Exception {
        Gate[] cols = {MIN, MAX, ANY, CONS, SUM};
        int rows = 0;
        for (String line : appendix()) {
            String[] c = line.split("\\|");
            if (c.length != 7) continue;
            String a = c[1].trim();
            if (!(a.equals("−") || a.equals("0") || a.equals("+"))) continue;
            for (int k = 0; k < cols.length; k++) {
                String[] outs = c[2 + k].split(",");
                assertEquals(3, outs.length);
                for (int b = 0; b < 3; b++) assertEquals(idx(outs[b]), cols[k].eval(idx(a), b), cols[k].name() + " a=" + a + " b=" + b);
            }
            rows++;
        }
        assertEquals(3, rows);
    }
}
