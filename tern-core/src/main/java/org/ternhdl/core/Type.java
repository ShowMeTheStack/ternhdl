package org.ternhdl.core;

/** A signal type: a vector of trits, or a Bit (binary, stored as a trit restricted to {O, P}). */
public record Type(boolean isBit, int width) {
    public static final Type BIT = new Type(true, 1);
    public static Type trits(int n) {
        if (n < 1) throw new IllegalArgumentException("width must be >= 1");
        return new Type(false, n);
    }
    public static final Type TRIT = trits(1);
    @Override public String toString() { return isBit ? "Bit" : "Trit" + (width == 1 ? "" : "<" + width + ">"); }
}
