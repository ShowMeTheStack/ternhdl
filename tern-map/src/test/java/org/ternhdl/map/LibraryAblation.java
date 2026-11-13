package org.ternhdl.map;

import java.util.*;
import static org.ternhdl.map.Cell.*;

/** Exact-synthesis statistics for alternative cell libraries (not a test). */
public final class LibraryAblation {
    public static void main(String[] a) {
        Map<String, List<Cell>> libs = new LinkedHashMap<>();
        libs.put("GT full (14 cells)", ExactDb.gtFull());
        libs.put("GT without negated binary", List.of(STI, PTI, NTI, ICI, DCI, ISZERO, MIN, MAX, ANY, CONS, SUM));
        libs.put("Park-like: 6 unary + MIN MAX ANY CONS SUM", List.of(STI, PTI, NTI, ICI, DCI, ISZERO, MIN, MAX, ANY, CONS, SUM));
        libs.put("tritflow-like: STI NMIN NMAX NANY", List.of(STI, NMIN, NMAX, NANY));
        libs.put("Kleene + cycle: STI MIN MAX ICI", List.of(STI, MIN, MAX, ICI));
        libs.put("minimal pair: ICI MIN", List.of(ICI, MIN));
        libs.put("adder set: STI SUM CONS ANY", List.of(STI, SUM, CONS, ANY));
        libs.put("Kleene only: STI MIN MAX", List.of(STI, MIN, MAX));
        for (var e : libs.entrySet()) {
            ExactDb db = new ExactDb(e.getValue());
            System.out.printf("ABLATION | %s | reachable %d | max %s | mean %s | %.1fs%n", e.getKey(), db.reachable(),
                db.reachable() == 0 ? "-" : String.valueOf(db.maxCost()), db.reachable() == 0 ? "-" : String.format("%.3f", db.meanCost()), db.seconds);
        }
    }
}
