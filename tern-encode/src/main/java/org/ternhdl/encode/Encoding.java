package org.ternhdl.encode;

import org.ternhdl.core.Logic3;

/** Two-bit trit encodings (spec section 9). Codes are indexed by level N, O, P. */
public enum Encoding {
    /** Park-style binary-coded ternary: O=00, P=01, N=11; 10 illegal. */
    E0(new int[]{3, 0, 1}, 2),
    /** Dual-rail sign (neg, pos): O=00, P=01, N=10; 11 illegal. Default. */
    E1(new int[]{2, 0, 1}, 3),
    /** Offset binary: N=00, O=01, P=10; 11 illegal. */
    E2(new int[]{0, 1, 2}, 3);

    private final int[] code;
    public final int illegal;

    Encoding(int[] code, int illegal) { this.code = code; this.illegal = illegal; }

    public int code(Logic3 level) {
        if (!level.isLevel()) throw new IllegalArgumentException("only levels have codes: " + level);
        return code[level.ordinal()];
    }

    /** The level for a code, or null for the illegal code. */
    public Logic3 decode(int c) {
        for (int i = 0; i < 3; i++) if (code[i] == c) return Logic3.values()[i];
        return null;
    }

    /** Two-character binary string of the code. */
    public String bits(Logic3 level) {
        int c = code(level);
        return "" + (c >> 1 & 1) + (c & 1);
    }
}
