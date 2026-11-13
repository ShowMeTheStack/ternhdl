package org.ternhdl.map;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.Logic3;

class ExactDbTest {
    @Test
    void fullLibraryReachesAllFunctionsAndRecipesAreCorrect() {
        ExactDb db = new ExactDb(ExactDb.gtFull());
        System.out.printf("EXACTDB gtFull reachable=%d max=%d mean=%.3f hist=%s %.1fs%n", db.reachable(), db.maxCost(), db.meanCost(), Arrays.toString(db.histogram()), db.seconds);
        assertEquals(ExactDb.N, db.reachable());
        // every recipe evaluates to its function, and its tree cost matches
        for (int f = 0; f < ExactDb.N; f++) {
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) assertEquals(ExactDb.at(f, a, b), eval(db, f, a, b), "f=" + f);
            assertEquals(db.cost[f], treeCost(db, f), "cost f=" + f);
        }
    }

    static int eval(ExactDb db, int f, int a, int b) {
        ExactDb.Recipe r = db.recipe[f];
        if (r.cell() == null) return ExactDb.at(f, a, b);
        if (r.b() < 0) return r.cell().level(eval(db, r.a(), a, b) - 1) + 1;
        return r.cell().level(eval(db, r.a(), a, b) - 1, eval(db, r.b(), a, b) - 1) + 1;
    }

    static int treeCost(ExactDb db, int f) {
        ExactDb.Recipe r = db.recipe[f];
        if (r.cell() == null) return 0;
        return r.cell().area + treeCost(db, r.a()) + (r.b() < 0 ? 0 : treeCost(db, r.b()));
    }
}
