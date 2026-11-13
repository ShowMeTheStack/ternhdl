package org.ternhdl.sim;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** VCD writer with string-valued trit signals (symbols - 0 + U X F, most significant first) and a binary clk. */
public final class VcdWriter implements Closeable {
    private final BufferedWriter out;
    private final String scope;
    private final LinkedHashMap<String, String> ids = new LinkedHashMap<>();
    private final Map<String, String> last = new HashMap<>();
    private String clkId;
    private int lastClk = -1;
    private long lastTime = -1;
    private boolean headerDone;
    private final List<String[]> decls = new ArrayList<>();

    public VcdWriter(Path file, String scope) throws IOException {
        this.out = Files.newBufferedWriter(file);
        this.scope = scope;
    }

    private static String idFor(int n) {
        StringBuilder sb = new StringBuilder();
        do { sb.append((char) ('!' + n % 90)); n /= 90; } while (n > 0);
        return sb.toString();
    }

    public void declare(String name, int width) { ids.put(name, idFor(ids.size())); decls.add(new String[]{name, String.valueOf(width)}); }
    public void declareClock() { clkId = "~"; }

    public void endDefinitions() throws IOException {
        out.write("$date TernHDL $end\n$version TernHDL VcdWriter $end\n$timescale 1ns $end\n$scope module " + scope + " $end\n");
        for (String[] d : decls) out.write("$var string 1 " + ids.get(d[0]) + " " + d[0] + " $end\n");
        out.write("$var wire 1 " + clkId + " clk $end\n$upscope $end\n$enddefinitions $end\n");
        headerDone = true;
    }

    public void dump(long time, int clk, Map<String, String> values) {
        try {
            if (!headerDone) throw new IllegalStateException("definitions not ended");
            StringBuilder sb = new StringBuilder();
            if (time != lastTime) sb.append('#').append(time).append('\n');
            if (clk != lastClk) { sb.append(clk).append(clkId).append('\n'); lastClk = clk; }
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (!e.getValue().equals(last.get(e.getKey()))) {
                    sb.append('s').append(e.getValue()).append(' ').append(ids.get(e.getKey())).append('\n');
                    last.put(e.getKey(), e.getValue());
                }
            }
            lastTime = time;
            out.write(sb.toString());
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    @Override public void close() throws IOException { out.close(); }
}
