package org.ternhdl.core;

/** Simulation value: three levels plus U (uninitialized), X (unknown/conflict), F (floating). See spec section 2. */
public enum Logic3 {
    N('-'), O('0'), P('+'), U('U'), X('X'), F('F');

    public final char sym;
    Logic3(char c) { sym = c; }

    public boolean isLevel() { return ordinal() < 3; }
    /** Balanced value -1, 0, +1 (levels only). */
    public int bal() {
        if (!isLevel()) throw new IllegalStateException(name() + " is not a level");
        return ordinal() - 1;
    }
    public static Logic3 ofBal(int b) { return values()[b + 1]; }

    public static Logic3 parse(char c) {
        return switch (c) {
            case '-', 'N', 'n' -> N; case '0', 'O', 'o' -> O; case '+', 'P', 'p' -> P;
            case 'U', 'u' -> U; case 'X', 'x' -> X; case 'F', 'f' -> F;
            default -> throw new IllegalArgumentException("bad trit symbol: " + c);
        };
    }

    /** Parse a most-significant-digit-first string into a little-endian array (index i has weight 3^i). */
    public static Logic3[] parseVec(String msbFirst) {
        int n = msbFirst.length();
        Logic3[] v = new Logic3[n];
        for (int i = 0; i < n; i++) v[n - 1 - i] = parse(msbFirst.charAt(i));
        return v;
    }

    /** Most-significant-digit-first string of a little-endian array. */
    public static String str(Logic3[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = v.length - 1; i >= 0; i--) sb.append(v[i].sym);
        return sb.toString();
    }
}
