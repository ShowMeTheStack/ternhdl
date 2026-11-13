package org.ternhdl.core;

/** RTL IR operations. Wiring ops (SLICE, CONCAT, SHL, SHR, REG, INPUT) preserve U; every other op maps U to X. */
public enum Op {
    INPUT, CONST,
    /** Digit-wise primitive (same-width operands). */
    PRIM,
    /** Balanced add, wrapping at the operand width. args: a, b [, cin (1 trit)]. */
    ADD,
    /** Shift toward MSB by aux digits, O fill, high digits dropped. */
    SHL,
    /** Drop aux low digits, O fill at MSB. */
    SHR,
    /** Three-way compare, 1-trit result sign(a - b); scan from MSB, unknown pair before a difference gives X. */
    CMP,
    /** args MSB part first. */
    CONCAT,
    /** digits aux2..aux (low..high inclusive). */
    SLICE,
    /** 1-trit to Bit: aux 0 isPos, 1 isNeg, 2 isZero. */
    TOBIT,
    /** Bit to 1 trit: aux 0 maps 0->O,1->P ; aux 1 maps 0->N,1->P. */
    TOTRIT,
    BNOT,
    /** args: select (1 trit), a, b, c; select N->a, O->b, P->c. */
    MUXT,
    /** args: select (Bit), a, b; select 1 (P) -> a, 0 (O) -> b. */
    MUXB,
    /** Register read (aux = register index). */
    REG,
    /** Asynchronous memory read (aux = memory index, args: address). */
    MEMRD
}
