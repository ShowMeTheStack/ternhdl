package org.ternhdl.hls;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import java.net.URI;
import java.util.*;
import javax.tools.*;

/** Parses a Java static method with the JDK compiler tree API and lowers it to the HLS IR. */
final class Front {
    private final Ir ir = new Ir();
    private Ir.Block cur;
    private Map<String, Integer> map = new HashMap<>();
    private Set<String> assigned = new LinkedHashSet<>();
    private boolean terminated;
    private boolean strength = true;

    static Ir parse(String source, String method) { return parse(source, method, true); }

    static Ir parse(String source, String method, boolean strengthReduce) {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        JavaFileObject src = new SimpleJavaFileObject(URI.create("string:///Kernel.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        JavacTask task = (JavacTask) jc.getTask(null, null, null, List.of("-proc:none"), null, List.of(src));
        try {
            for (CompilationUnitTree cu : task.parse())
                for (Tree t : cu.getTypeDecls())
                    if (t instanceof ClassTree ct)
                        for (Tree m : ct.getMembers())
                            if (m instanceof MethodTree mt && mt.getName().contentEquals(method)) { Front f = new Front(); f.strength = strengthReduce; return f.lower(mt); }
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        throw new IllegalArgumentException("method " + method + " not found");
    }

    static int annot(ModifiersTree mods, String name, int dflt) {
        for (AnnotationTree a : mods.getAnnotations()) {
            String n = a.getAnnotationType().toString();
            if (n.equals(name) || n.endsWith("." + name)) {
                ExpressionTree e = a.getArguments().get(0);
                if (e instanceof AssignmentTree as) e = as.getExpression();
                return Integer.parseInt(e.toString().trim());
            }
        }
        return dflt;
    }

    static int widthOf(long c) { int n = 1; long h = 1; while (Math.abs(c) > h) { n++; h = h * 3 + 1; } return n; }

    private Ir lower(MethodTree mt) {
        String rt = mt.getReturnType() == null ? "void" : mt.getReturnType().toString();
        for (VariableTree p : mt.getParameters()) {
            String type = p.getType().toString(), name = p.getName().toString();
            int w = annot(p.getModifiers(), "Trits", 27);
            if (type.endsWith("[]")) ir.arrays.put(name, new Ir.Arr(name, w, annot(p.getModifiers(), "Depth", 5)));
            else { ir.vars.put(name, new Ir.Var(name, w)); ir.params.add(name); }
        }
        ir.retWidth = rt.equals("void") ? 1 : 27;
        cur = newBlock();
        stmt(mt.getBody());
        if (!terminated) { cur.term = "RET"; cur.ret = -1; endBlock(); }
        return ir;
    }

    // ---------------------------------------------------------------- blocks
    private Ir.Block newBlock() {
        Ir.Block b = new Ir.Block(ir.blocks.size());
        ir.blocks.add(b);
        map = new HashMap<>(); assigned = new LinkedHashSet<>(); terminated = false;
        cur = b;
        return b;
    }

    private void endBlock() { for (String v : assigned) cur.varOut.put(v, map.get(v)); }

    private void gotoNew(Ir.Block target) { cur.term = "GOTO"; cur.tgtTrue = target.id; endBlock(); }

    private int val(Ir.K k, int[] args, long c, String name, int w, boolean bit) {
        Ir.Val v = new Ir.Val(cur.vals.size(), k, args, c, name, Math.max(1, Math.min(27, w)), bit);
        cur.vals.add(v);
        return v.id;
    }

    private Ir.Val v(int id) { return cur.vals.get(id); }

    private int readVar(String name) {
        Integer x = map.get(name);
        if (x != null) return x;
        Ir.Var var = ir.vars.get(name);
        if (var == null) throw new IllegalArgumentException("unknown variable " + name);
        int id = val(Ir.K.VAR, new int[0], 0, name, var.width(), false);
        map.put(name, id);
        return id;
    }

    private int fit(int id, int w) { return v(id).width == w ? id : val(Ir.K.WIDEN, new int[]{id}, w, null, w, false); }

    private void assign(String name, int value) {
        Ir.Var var = ir.vars.get(name);
        map.put(name, fit(value, var.width()));
        assigned.add(name);
    }

    // ---------------------------------------------------------------- statements
    private void stmt(StatementTree s) {
        if (terminated) throw new IllegalArgumentException("unreachable statement after return: " + s);
        switch (s) {
            case BlockTree b -> { for (StatementTree x : b.getStatements()) { if (terminated) break; stmt(x); } }
            case VariableTree vt -> {
                int w = annot(vt.getModifiers(), "Trits", 27);
                ir.vars.putIfAbsent(vt.getName().toString(), new Ir.Var(vt.getName().toString(), w));
                if (vt.getInitializer() != null) assign(vt.getName().toString(), expr(vt.getInitializer()));
            }
            case ExpressionStatementTree es -> effect(es.getExpression());
            case IfTree it -> {
                int c = expr(it.getCondition());
                Ir.Block before = cur;
                before.term = "BR"; before.cond = c; endBlock();
                Ir.Block thenB = newBlock();
                before.tgtTrue = thenB.id;
                stmt(it.getThenStatement());
                Ir.Block thenEnd = terminated ? null : cur;
                Ir.Block elseEnd = null;
                Ir.Block elseB = null;
                if (it.getElseStatement() != null) {
                    if (thenEnd != null) endBlock();
                    elseB = newBlock();
                    before.tgtFalse = elseB.id;
                    stmt(it.getElseStatement());
                    elseEnd = terminated ? null : cur;
                    if (elseEnd != null) endBlock();
                } else if (thenEnd != null) endBlock();
                Ir.Block join = newBlock();
                if (elseB == null) before.tgtFalse = join.id;
                if (thenEnd != null) { thenEnd.term = "GOTO"; thenEnd.tgtTrue = join.id; }
                if (elseEnd != null) { elseEnd.term = "GOTO"; elseEnd.tgtTrue = join.id; }
            }
            case ForLoopTree ft -> {
                for (StatementTree init : ft.getInitializer()) stmt(init);
                loop(ft.getCondition(), ft.getStatement(), ft.getUpdate());
            }
            case WhileLoopTree wt -> loop(wt.getCondition(), wt.getStatement(), List.of());
            case ReturnTree rt -> {
                cur.term = "RET";
                cur.ret = rt.getExpression() == null ? -1 : fit(expr(rt.getExpression()), ir.retWidth);
                endBlock();
                terminated = true;
            }
            case EmptyStatementTree e -> { }
            default -> throw new IllegalArgumentException("unsupported statement: " + s.getKind() + " " + s);
        }
    }

    private void loop(ExpressionTree cond, StatementTree body, List<? extends ExpressionStatementTree> update) {
        Ir.Block pre = cur;
        pre.term = "GOTO";
        endBlock();                     // record the predecessor's modified variables before opening the header
        Ir.Block head = newBlock();
        pre.tgtTrue = head.id;
        int c = expr(cond);
        head.term = "BR"; head.cond = c; endBlock();
        Ir.Block bodyB = newBlock();
        head.tgtTrue = bodyB.id;
        stmt(body);
        if (!terminated) {
            for (ExpressionStatementTree u : update) effect(u.getExpression());
            cur.term = "GOTO"; cur.tgtTrue = head.id; endBlock();
        }
        Ir.Block exit = newBlock();
        head.tgtFalse = exit.id;
    }

    private void effect(ExpressionTree e) {
        switch (e) {
            case AssignmentTree as -> store(as.getVariable(), expr(as.getExpression()));
            case CompoundAssignmentTree ca -> {
                int lhs = expr(ca.getVariable()), rhs = expr(ca.getExpression());
                int r = switch (ca.getKind()) {
                    case PLUS_ASSIGNMENT -> arith(Ir.K.ADD, lhs, rhs);
                    case MINUS_ASSIGNMENT -> arith(Ir.K.SUB, lhs, rhs);
                    case MULTIPLY_ASSIGNMENT -> mul(lhs, rhs);
                    default -> throw new IllegalArgumentException("unsupported compound assignment " + ca.getKind());
                };
                store(ca.getVariable(), r);
            }
            case UnaryTree ut when ut.getKind() == Tree.Kind.POSTFIX_INCREMENT || ut.getKind() == Tree.Kind.PREFIX_INCREMENT
                    || ut.getKind() == Tree.Kind.POSTFIX_DECREMENT || ut.getKind() == Tree.Kind.PREFIX_DECREMENT -> {
                boolean inc = ut.getKind() == Tree.Kind.POSTFIX_INCREMENT || ut.getKind() == Tree.Kind.PREFIX_INCREMENT;
                int x = expr(ut.getExpression());
                store(ut.getExpression(), arith(inc ? Ir.K.ADD : Ir.K.SUB, x, constant(1)));
            }
            default -> throw new IllegalArgumentException("unsupported expression statement " + e);
        }
    }

    private void store(ExpressionTree target, int value) {
        if (target instanceof IdentifierTree id) assign(id.getName().toString(), value);
        else if (target instanceof ArrayAccessTree aa) {
            String mem = aa.getExpression().toString();
            Ir.Arr arr = ir.arrays.get(mem);
            if (arr == null) throw new IllegalArgumentException("unknown array " + mem);
            int idx = expr(aa.getIndex()), dat = fit(value, arr.width());
            cur.writes.add(new Ir.MemWr(mem, idx, dat, cur.writes.size(), cur.vals.size()));
        } else throw new IllegalArgumentException("unsupported assignment target " + target);
    }

    // ---------------------------------------------------------------- expressions
    private int constant(long c) { return val(Ir.K.CONST, new int[0], c, null, widthOf(c), false); }

    private int arith(Ir.K k, int a, int b) {
        int w = Math.max(v(a).width, v(b).width) + 1;
        return val(k, new int[]{a, b}, 0, null, w, false);
    }

    /** Multiplication: by a constant -> shifts and adds over its balanced digits; otherwise a digit-serial sum of MULD terms. */
    private int mul(int a, int b) {
        if (strength && v(b).k == Ir.K.CONST) return mulConst(a, v(b).c);
        if (strength && v(a).k == Ir.K.CONST) return mulConst(b, v(a).c);
        int x = a, y = b;
        if (v(y).width > v(x).width) { int t = x; x = y; y = t; }        // digits of the narrower operand
        int acc = -1;
        int w = Math.min(27, v(x).width + v(y).width);
        for (int i = 0; i < v(y).width; i++) {
            int term = val(Ir.K.MULD, new int[]{x, y}, i, null, Math.min(27, v(x).width + i + 1), false);
            acc = acc < 0 ? term : val(Ir.K.ADD, new int[]{acc, term}, 0, null, w, false);
        }
        return acc;
    }

    private int mulConst(int x, long c) {
        if (c == 0) return constant(0);
        int acc = -1;
        int i = 0;
        long r = c;
        while (r != 0) {
            int dgt = (int) Math.floorMod(r, 3); if (dgt == 2) dgt = -1;
            r = (r - dgt) / 3;
            if (dgt != 0) {
                int term = i == 0 ? x : val(Ir.K.SHL, new int[]{x}, i, null, Math.min(27, v(x).width + i), false);
                if (acc < 0) acc = dgt > 0 ? term : val(Ir.K.NEG, new int[]{term}, 0, null, v(term).width, false);
                else acc = arith(dgt > 0 ? Ir.K.ADD : Ir.K.SUB, acc, term);
            }
            i++;
        }
        return acc;
    }

    private int expr(ExpressionTree e) {
        return switch (e) {
            case ParenthesizedTree p -> expr(p.getExpression());
            case TypeCastTree tc -> expr(tc.getExpression());
            case LiteralTree lt -> {
                Object o = lt.getValue();
                if (o instanceof Boolean bo) yield val(Ir.K.CONST, new int[0], bo ? 1 : 0, null, 1, true);
                yield constant(((Number) o).longValue());
            }
            case IdentifierTree id -> readVar(id.getName().toString());
            case UnaryTree ut -> switch (ut.getKind()) {
                case UNARY_MINUS -> { int x = expr(ut.getExpression()); yield val(Ir.K.NEG, new int[]{x}, 0, null, v(x).width, false); }
                case UNARY_PLUS -> expr(ut.getExpression());
                case LOGICAL_COMPLEMENT -> { int x = expr(ut.getExpression()); yield val(Ir.K.NOT, new int[]{x}, 0, null, 1, true); }
                default -> throw new IllegalArgumentException("unsupported unary " + ut.getKind());
            };
            case BinaryTree bt -> {
                int a = expr(bt.getLeftOperand()), b = expr(bt.getRightOperand());
                yield switch (bt.getKind()) {
                    case PLUS -> arith(Ir.K.ADD, a, b);
                    case MINUS -> arith(Ir.K.SUB, a, b);
                    case MULTIPLY -> mul(a, b);
                    case LESS_THAN -> val(Ir.K.LT, new int[]{a, b}, 0, null, 1, true);
                    case LESS_THAN_EQUAL -> val(Ir.K.LE, new int[]{a, b}, 0, null, 1, true);
                    case GREATER_THAN -> val(Ir.K.GT, new int[]{a, b}, 0, null, 1, true);
                    case GREATER_THAN_EQUAL -> val(Ir.K.GE, new int[]{a, b}, 0, null, 1, true);
                    case EQUAL_TO -> val(Ir.K.EQ, new int[]{a, b}, 0, null, 1, true);
                    case NOT_EQUAL_TO -> val(Ir.K.NE, new int[]{a, b}, 0, null, 1, true);
                    case CONDITIONAL_AND -> val(Ir.K.AND, new int[]{a, b}, 0, null, 1, true);
                    case CONDITIONAL_OR -> val(Ir.K.OR, new int[]{a, b}, 0, null, 1, true);
                    default -> throw new IllegalArgumentException("unsupported operator " + bt.getKind());
                };
            }
            case ConditionalExpressionTree ce -> {
                int c = expr(ce.getCondition()), t = expr(ce.getTrueExpression()), f = expr(ce.getFalseExpression());
                yield val(Ir.K.SEL, new int[]{c, t, f}, 0, null, Math.max(v(t).width, v(f).width), false);
            }
            case ArrayAccessTree aa -> {
                String mem = aa.getExpression().toString();
                Ir.Arr arr = ir.arrays.get(mem);
                if (arr == null) throw new IllegalArgumentException("unknown array " + mem);
                int idx = expr(aa.getIndex());
                int id = val(Ir.K.MEMRD, new int[]{idx}, 0, mem, arr.width(), false);
                for (int k = cur.writes.size() - 1; k >= 0; k--) if (cur.writes.get(k).mem().equals(mem)) { v(id).after = k; break; }
                yield id;
            }
            case MethodInvocationTree mi -> {
                String m = mi.getMethodSelect().toString();
                List<? extends ExpressionTree> args = mi.getArguments();
                String n = m.contains(".") ? m.substring(m.lastIndexOf('.') + 1) : m;
                if (n.equals("shl") || n.equals("shr")) {
                    int x = expr(args.get(0));
                    int k = Integer.parseInt(args.get(1).toString().trim());
                    yield val(n.equals("shl") ? Ir.K.SHL : Ir.K.SHR, new int[]{x}, k, null, n.equals("shl") ? Math.min(27, v(x).width + k) : v(x).width, false);
                }
                int a = expr(args.get(0)), b = expr(args.get(1));
                Ir.K k = switch (n) { case "min" -> Ir.K.MIN; case "max" -> Ir.K.MAX; case "any" -> Ir.K.ANY; case "cons" -> Ir.K.CONS; case "tsum" -> Ir.K.TSUM;
                    default -> throw new IllegalArgumentException("unsupported call " + m); };
                yield val(k, new int[]{a, b}, 0, null, Math.max(v(a).width, v(b).width), false);
            }
            default -> throw new IllegalArgumentException("unsupported expression " + e.getKind() + ": " + e);
        };
    }
}
