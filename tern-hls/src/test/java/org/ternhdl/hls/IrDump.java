package org.ternhdl.hls;

public final class IrDump {
    public static void main(String[] a) throws Exception {
        Ir ir = Front.parse(HlsTest.source(), a[0]);
        for (Ir.Block b : ir.blocks) {
            System.out.printf("B%d term=%s cond=%d T=%d F=%d ret=%d varOut=%s writes=%d%n", b.id, b.term, b.cond, b.tgtTrue, b.tgtFalse, b.ret, b.varOut, b.writes.size());
            for (Ir.Val v : b.vals) System.out.printf("   v%d %s args=%s c=%d %s w=%d%n", v.id, v.k, java.util.Arrays.toString(v.args), v.c, v.name, v.width);
        }
    }
}
