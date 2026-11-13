package org.ternhdl.core;

/** One IR node. Arguments always refer to lower ids, so id order is a topological order. */
public record Node(int id, Op op, Type type, int[] args, Prim prim, int aux, int aux2, Logic3[] constant, String name) { }
