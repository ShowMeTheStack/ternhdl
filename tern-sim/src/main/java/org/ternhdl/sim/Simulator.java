package org.ternhdl.sim;

/** Minimal simulator interface (strings are most-significant-digit first, symbols - 0 + U X F). */
public interface Simulator {
    void poke(String input, String msbFirst);
    /** Value of an input, output, register or named node. */
    String peek(String name);
    /** Recompute combinational values. */
    void eval();
    /** One rising clock edge. */
    void step();
}
