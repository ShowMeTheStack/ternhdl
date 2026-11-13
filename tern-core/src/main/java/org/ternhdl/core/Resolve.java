package org.ternhdl.core;

import java.util.List;

/** Driver resolution (spec section 3). */
public final class Resolve {
    private Resolve() { }

    public enum Strength { FLOAT, WEAK, STRONG }

    public record Driver(Logic3 value, Strength strength) {
        public static final Driver NONE = new Driver(Logic3.F, Strength.FLOAT);
    }

    /** Combine two drivers; the result describes the pair as a single equivalent driver. */
    public static Driver combine(Driver a, Driver b) {
        if (a.strength() == Strength.FLOAT) return b.strength() == Strength.FLOAT ? Driver.NONE : b;
        if (b.strength() == Strength.FLOAT) return a;
        int c = a.strength().compareTo(b.strength());
        if (c > 0) return a;
        if (c < 0) return b;
        return a.value() == b.value() ? a : new Driver(Logic3.X, a.strength());
    }

    /** Value of a net with the given drivers; F when nothing drives it. */
    public static Logic3 resolve(List<Driver> drivers) {
        Driver acc = Driver.NONE;
        for (Driver d : drivers) acc = combine(acc, d);
        return acc.strength() == Strength.FLOAT ? Logic3.F : acc.value();
    }
}
