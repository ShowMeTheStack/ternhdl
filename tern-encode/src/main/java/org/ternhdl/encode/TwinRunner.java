package org.ternhdl.encode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.ternhdl.core.*;

/**
 * Builds the encoded twin with Verilator (-Wall, warnings fatal, --assert) and drives it with a line protocol:
 * each line holds one hex value per input (in declaration order); the harness evaluates, prints every output
 * (and debug port) as hex, then applies one rising clock edge. Ports are limited to 64 bits (32 trits).
 */
public final class TwinRunner {
    public record Result(int exit, List<String> lines, String stderr) { }

    private final Design d;
    private final SvEmitter.Options opt;
    private final Path dir;
    private final List<String> ins = new ArrayList<>();
    private final List<String> outs = new ArrayList<>();
    private final boolean hasClk;
    private Path exe;

    public TwinRunner(Design d, SvEmitter.Options opt, Path dir) {
        this.d = d; this.opt = opt; this.dir = dir;
        d.finish();
        ins.addAll(d.inputs().keySet());
        outs.addAll(d.outputs().keySet());
        if (opt.exposeAll()) for (Node nd : d.nodes()) outs.add(SvEmitter.dbg(nd.id()));
        String sv = SvEmitter.emit(d, opt);
        hasClk = sv.contains("input logic clk");
        for (String p : ins) check(p);
        for (String p : outs) check(p);
    }

    private void check(String port) {
        int w = width(port);
        if (w > 64) throw new IllegalArgumentException("port " + port + " is " + w + " bits; harness limit 64");
    }

    public int width(String port) {
        Integer id = d.inputs().get(port);
        if (id == null) id = d.outputs().get(port);
        if (id == null && port.startsWith("dbg_")) id = Integer.parseInt(port.substring(4));
        Type t = d.node(id).type();
        return t.isBit() ? 1 : 2 * t.width();
    }

    public List<String> inputs() { return ins; }
    public List<String> outputs() { return outs; }

    public static Path workRoot() {
        String w = System.getenv("TERN_WORK");
        return Path.of(w != null ? w : System.getProperty("java.io.tmpdir"), "verilator");
    }

    public synchronized void build() throws IOException, InterruptedException {
        if (exe != null) return;
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(d.name + ".sv"), SvEmitter.emit(d, opt));
        StringBuilder c = new StringBuilder();
        c.append("#include \"V").append(d.name).append(".h\"\n#include \"verilated.h\"\n#include <cstdio>\n#include <cstdint>\n");
        c.append("int main(int argc, char** argv) {\n  VerilatedContext* ctx = new VerilatedContext;\n  ctx->commandArgs(argc, argv);\n");
        c.append("  V").append(d.name).append("* t = new V").append(d.name).append("{ctx};\n");
        if (hasClk) c.append("  t->clk = 0;\n");
        c.append("  unsigned long long v;\n  while (true) {\n");
        for (int i = 0; i < ins.size(); i++) {
            c.append("    if (scanf(\"%llx\", &v) != 1) break;\n    t->").append(ins.get(i)).append(" = v;\n");
        }
        if (ins.isEmpty()) c.append("    if (scanf(\"%llx\", &v) != 1) break;\n");
        c.append("    t->eval();\n    printf(\"");
        for (int i = 0; i < outs.size(); i++) c.append(i > 0 ? " " : "").append("%llx");
        c.append("\\n\"");
        for (String o : outs) c.append(", (unsigned long long) t->").append(o);
        c.append(");\n");
        if (hasClk) c.append("    t->clk = 1; t->eval(); t->clk = 0; t->eval();\n");
        c.append("  }\n  t->final();\n  delete t;\n  delete ctx;\n  return 0;\n}\n");
        Files.writeString(dir.resolve("main.cpp"), c.toString());
        List<String> cmd = List.of("verilator", "--cc", "--exe", "--build", "-Wall", "--assert", "-O1", "-j", "4",
            "--top-module", d.name, d.name + ".sv", "main.cpp", "-o", "twin");
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        Files.writeString(dir.resolve("build.log"), log);
        if (rc != 0) throw new IOException("verilator build failed in " + dir + ":\n" + log);
        exe = dir.resolve("obj_dir").resolve("twin");
    }

    /** Runs raw lines (hex values per input) and returns the output lines. */
    public Result runRaw(List<String> lines) throws IOException, InterruptedException {
        build();
        Process p = new ProcessBuilder(exe.toString()).directory(dir.toFile()).start();
        Thread errReader;
        StringBuilder err = new StringBuilder();
        errReader = new Thread(() -> { try { err.append(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)); } catch (IOException ignored) { } });
        errReader.start();
        List<String> out = new ArrayList<>();
        Thread outReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l; while ((l = r.readLine()) != null) out.add(l);
            } catch (IOException ignored) { }
        });
        outReader.start();
        try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
            for (String l : lines) w.write(l + "\n");
        }
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("twin timed out"); }
        outReader.join(); errReader.join();
        return new Result(p.exitValue(), out, err.toString());
    }

    // ---------------------------------------------------------------- value packing
    public long pack(String input, Logic3[] le) {
        Type t = d.node(d.inputs().get(input)).type();
        if (t.isBit()) return le[0] == Logic3.P ? 1 : 0;
        long v = 0;
        for (int i = 0; i < le.length; i++) v |= (long) opt.enc().code(le[i]) << (2 * i);
        return v;
    }

    /** Decodes a port value into digits; illegal codes become null entries. */
    public Logic3[] unpack(String port, long v) {
        int w = width(port);
        Integer id = d.outputs().get(port);
        if (id == null && port.startsWith("dbg_")) id = Integer.parseInt(port.substring(4));
        if (d.node(id).type().isBit()) return new Logic3[]{(v & 1) != 0 ? Logic3.P : Logic3.O};
        Logic3[] r = new Logic3[w / 2];
        for (int i = 0; i < r.length; i++) r[i] = opt.enc().decode((int) (v >>> (2 * i) & 3));
        return r;
    }
}
