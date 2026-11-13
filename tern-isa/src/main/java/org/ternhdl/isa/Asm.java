package org.ternhdl.isa;

import java.util.*;

/**
 * Two-pass T27 assembler. Syntax: one statement per line, `label:` prefixes, `;` or `#` comments.
 * Directives: `.org A` (tryte address, multiple of 3), `.word v`, `.zero n` (n words), `.equ name v`.
 * Pseudo-instructions: LI rd, v (LUI+ADDI), MV, NOP, J, CALL, RET, BEQ/BNE/BLT/BLE/BGT/BGE/B.
 * Registers: x-13..x13 (x0 = zero) and aliases ra=x1, sp=x2, a0..a5 = x3..x8, t0..t12 = x-1..x-13.
 */
public final class Asm {
    public record Program(Map<Long, Long> words, long entry, Map<String, Long> labels) { }

    private final Map<String, Long> labels = new LinkedHashMap<>();
    private final Map<String, Long> equ = new HashMap<>();

    static final Map<String, Integer> OPS = Map.ofEntries(Map.entry("ADDI", 2), Map.entry("LUI", 3), Map.entry("SHLI", 4), Map.entry("SHRI", 5),
        Map.entry("LW", 6), Map.entry("LT", 7), Map.entry("SW", 8), Map.entry("ST", 9), Map.entry("JAL", 11), Map.entry("JALR", 12), Map.entry("CMPI", 13));
    static final Map<String, Integer> ALU = Map.ofEntries(Map.entry("ADD", 0), Map.entry("SUB", 1), Map.entry("MUL", 2), Map.entry("MULH", 3), Map.entry("MIN", 4),
        Map.entry("MAX", 5), Map.entry("ANY", 6), Map.entry("CONS", 7), Map.entry("TSUM", 8), Map.entry("CMP", 9), Map.entry("SHL", 10), Map.entry("SHR", 11),
        Map.entry("NEG", 12), Map.entry("PTI", -1), Map.entry("NTI", -2), Map.entry("ICI", -3), Map.entry("DCI", -4), Map.entry("ISZ", -5), Map.entry("SLT3", -6));
    static final Map<String, int[]> BR = Map.of("BLT", new int[]{1, 0, 0}, "BEQ", new int[]{0, 1, 0}, "BGT", new int[]{0, 0, 1},
        "BNE", new int[]{1, 0, 1}, "BLE", new int[]{1, 1, 0}, "BGE", new int[]{0, 1, 1}, "B", new int[]{1, 1, 1});

    public static Program assemble(String src) { return new Asm().run(src); }

    static int reg(String s) {
        s = s.trim().toLowerCase();
        switch (s) { case "zero": return 0; case "ra": return 1; case "sp": return 2; }
        if (s.matches("a[0-5]")) return 3 + (s.charAt(1) - '0');
        if (s.matches("t([0-9]|1[0-2])")) return -1 - Integer.parseInt(s.substring(1));
        if (s.matches("x-?\\d+")) { int r = Integer.parseInt(s.substring(1)); if (r < -13 || r > 13) throw new IllegalArgumentException("register " + s); return r; }
        throw new IllegalArgumentException("not a register: " + s);
    }

    long num(String s) {
        s = s.trim();
        if (equ.containsKey(s)) return equ.get(s);
        if (labels.containsKey(s)) return labels.get(s);
        if (s.startsWith("0t")) { long v = 0; for (char c : s.substring(2).toCharArray()) v = v * 3 + (c == '+' ? 1 : c == '-' ? -1 : 0); return v; }
        return Long.parseLong(s);
    }

    private List<String[]> lines(String src) {
        List<String[]> out = new ArrayList<>();
        int n = 0;
        for (String raw : src.split("\n")) {
            n++;
            String l = raw.replaceAll("[;#].*$", "").trim();
            while (l.matches("^[A-Za-z_][A-Za-z0-9_]*:.*")) {
                int c = l.indexOf(':');
                out.add(new String[]{"LABEL", l.substring(0, c).trim(), String.valueOf(n)});
                l = l.substring(c + 1).trim();
            }
            if (l.isEmpty()) continue;
            String[] p = l.split("\\s+", 2);
            out.add(new String[]{p[0].toUpperCase(), p.length > 1 ? p[1] : "", String.valueOf(n)});
        }
        return out;
    }

    private int size(String op) { return switch (op) { case "LI", "CALLFAR" -> 2; case ".ZERO", ".ORG", ".EQU", "LABEL" -> 0; default -> 1; }; }

    private Program run(String src) {
        List<String[]> ls = lines(src);
        long pc = 0;
        for (String[] l : ls) {   // pass 1
            switch (l[0]) {
                case "LABEL" -> labels.put(l[1], pc);
                case ".ORG" -> pc = num(l[1]);
                case ".EQU" -> { String[] a = l[1].split(","); equ.put(a[0].trim(), num(a[1])); }
                case ".ZERO" -> pc += 3 * num(l[1]);
                default -> pc += 3L * size(l[0]);
            }
        }
        Map<Long, Long> words = new TreeMap<>();
        pc = 0;
        for (String[] l : ls) {   // pass 2
            try {
                switch (l[0]) {
                    case "LABEL", ".EQU" -> { }
                    case ".ORG" -> pc = num(l[1]);
                    case ".ZERO" -> { for (long k = 0; k < num(l[1]); k++) { words.put(pc, 0L); pc += 3; } }
                    case ".WORD" -> { words.put(pc, T27.wrap(num(l[1]))); pc += 3; }
                    default -> { for (long w : encode(l[0], l[1], pc)) { words.put(pc, w); pc += 3; } }
                }
            } catch (RuntimeException e) { throw new IllegalArgumentException("line " + l[2] + ": " + l[0] + " " + l[1] + ": " + e.getMessage(), e); }
        }
        return new Program(words, labels.getOrDefault("_start", 0L), labels);
    }

    static long word(int op, int[] fields) {   // fields: list of (value, offset, length) triples
        int[] t = new int[27];
        put(t, op, 0, 3);
        for (int i = 0; i < fields.length; i += 3) put(t, fields[i], fields[i + 1], fields[i + 2]);
        return T27.value(t);
    }

    static void put(int[] t, long v, int off, int len) {
        long h = (T27.pow3(len) - 1) / 2;
        if (v < -h || v > h) throw new IllegalArgumentException("value " + v + " does not fit " + len + " trits");
        int[] d = T27.trits(v, len);
        System.arraycopy(d, 0, t, off, len);
    }

    static long wordI(int op, int rd, int ra, long imm) {
        int[] t = new int[27];
        put(t, op, 0, 3); put(t, rd, 3, 3); put(t, ra, 6, 3); put(t, imm, 9, 18);
        return T27.value(t);
    }

    private long off(String target, long pc) {
        long a = num(target);
        if ((a - pc) % 3 != 0) throw new IllegalArgumentException("target not word aligned");
        return (a - pc) / 3;
    }

    private long[] encode(String op, String args, long pc) {
        String[] a = args.isEmpty() ? new String[0] : args.split(",");
        for (int i = 0; i < a.length; i++) a[i] = a[i].trim();
        if (ALU.containsKey(op)) {
            int rd = reg(a[0]), ra = reg(a[1]), rb = a.length > 2 ? reg(a[2]) : 0;
            int[] t = new int[27];
            put(t, 1, 0, 3); put(t, rd, 3, 3); put(t, ra, 6, 3); put(t, rb, 9, 3); put(t, ALU.get(op), 12, 3);
            return new long[]{T27.value(t)};
        }
        if (BR.containsKey(op)) {
            int[] m = BR.get(op);
            int[] t = new int[27];
            put(t, 10, 0, 3);
            int ra = op.equals("B") ? 0 : reg(a[0]), rb = op.equals("B") ? 0 : reg(a[1]);
            put(t, ra, 3, 3); put(t, rb, 6, 3);
            for (int k = 0; k < 3; k++) t[9 + k] = m[k];
            put(t, off(op.equals("B") ? a[0] : a[2], pc), 12, 15);
            return new long[]{T27.value(t)};
        }
        switch (op) {
            case "HALT": return new long[]{0};
            case "NOP": return new long[]{wordI(2, 0, 0, 0)};
            case "MV": return new long[]{wordI(2, reg(a[0]), reg(a[1]), 0)};
            case "LI": {
                long v = num(a[1]);
                long lo = T27.wrap(v, 9), hi = (v - lo) / T27.MT;
                return new long[]{wordI(3, reg(a[0]), 0, hi), wordI(2, reg(a[0]), reg(a[0]), lo)};
            }
            case "J": return jal(0, a[0], pc);
            case "CALL": return jal(1, a[0], pc);
            case "RET": return new long[]{wordI(12, 0, 1, 0)};
            case "JAL": return jal(reg(a[0]), a[1], pc);
            case "LUI": return new long[]{wordI(3, reg(a[0]), 0, num(a[1]))};
            case "JALR": return new long[]{wordI(12, reg(a[0]), reg(a[1]), a.length > 2 ? num(a[2]) : 0)};
            case "LW": case "LT": case "SW": case "ST": {
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(.*)\\((.*)\\)").matcher(a[1]);
                if (!mm.matches()) throw new IllegalArgumentException("expected imm(reg)");
                long imm = mm.group(1).isBlank() ? 0 : num(mm.group(1));
                return new long[]{wordI(OPS.get(op), reg(a[0]), reg(mm.group(2)), imm)};
            }
            case "SHLI": case "SHRI": {
                long k = num(a[2]);
                if (k < 0 || k > 26) throw new IllegalArgumentException("shift amount 0..26");
                return new long[]{wordI(OPS.get(op), reg(a[0]), reg(a[1]), k - 13)};   // amount = unbalanced value of the low 3 trits
            }
            default:
                if (OPS.containsKey(op)) return new long[]{wordI(OPS.get(op), reg(a[0]), reg(a[1]), num(a[2]))};
                throw new IllegalArgumentException("unknown mnemonic " + op);
        }
    }

    private long[] jal(int rd, String target, long pc) {
        int[] t = new int[27];
        put(t, 11, 0, 3); put(t, rd, 3, 3); put(t, off(target, pc), 6, 21);
        return new long[]{T27.value(t)};
    }
}
