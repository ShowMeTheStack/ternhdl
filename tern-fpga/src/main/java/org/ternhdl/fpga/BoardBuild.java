package org.ternhdl.fpga;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.ternhdl.core.Design;
import org.ternhdl.encode.Encoding;
import org.ternhdl.stdlib.Benchmarks;

/** Generates the harness for a benchmark and (unless "lint") builds the bitstream with Vivado in docker (not a test). */
public final class BoardBuild {
    public static Path dir(String name, Encoding e) { return Path.of(System.getProperty("user.home"), "ternhdl-viv", "board", name + "-" + e); }

    /** Writes the harness for any design and optionally builds it (fclk in MHz). Returns the build output. */
    public static String build(Design d, Encoding enc, Path dir, int fclk, boolean lintOnly) throws Exception {
        BoardSoc.Layout l = BoardSoc.layout(d);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("dut.sv"), BoardSoc.twinWithEnable(d, enc));
        Files.writeString(dir.resolve("board_top.sv"), BoardSoc.top(d, l));
        Files.writeString(dir.resolve("board_wrap.v"), BoardSoc.verilogWrapper());
        Files.writeString(dir.resolve("build.tcl"), BoardSoc.vivadoTcl(fclk));
        String cmd = lintOnly
            ? "cd " + dir + " && verilator --lint-only -Wall -Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM -Wno-DECLFILENAME --top-module board_top dut.sv board_top.sv"
            : "cd " + dir + " && sudo docker run --rm --cpuset-cpus=16-31 -v " + dir + ":/w -w /w kkrizka/vivado:2020.1 bash -c "
              + "'source /opt/Xilinx/Vivado/2020.1/settings64.sh >/dev/null; vivado -mode batch -nojournal -nolog -source build.tcl > vivado.log 2>&1'; sudo chown -R $(id -u):$(id -g) " + dir
              + "; grep -E \"WNS|TERN_BUILD_DONE|ERROR\" " + dir + "/vivado.log | head";
        Process p = new ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(7200, TimeUnit.SECONDS);
        return out;
    }

    public static void main(String[] a) throws Exception {
        String name = a[0];
        Encoding enc = Encoding.valueOf(a.length > 1 ? a[1] : "E1");
        boolean lintOnly = a.length > 2 && a[2].equals("lint");
        Design d = Benchmarks.all().get(name).get();
        BoardSoc.Layout l = BoardSoc.layout(d);
        Path dir = dir(name, enc);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("dut.sv"), BoardSoc.twinWithEnable(d, enc));
        Files.writeString(dir.resolve("board_top.sv"), BoardSoc.top(d, l));
        Files.writeString(dir.resolve("board_wrap.v"), BoardSoc.verilogWrapper());
        Files.writeString(dir.resolve("build.tcl"), BoardSoc.vivadoTcl(50));
        StringBuilder lay = new StringBuilder();
        for (BoardSoc.Field f : l.in()) lay.append("in ").append(f.port()).append(' ').append(f.word()).append(' ').append(f.lsb()).append(' ').append(f.width()).append('\n');
        for (BoardSoc.Field f : l.out()) lay.append("out ").append(f.port()).append(' ').append(f.word()).append(' ').append(f.lsb()).append(' ').append(f.width()).append('\n');
        Files.writeString(dir.resolve("layout.txt"), lay.toString());
        String cmd = lintOnly
            ? "cd " + dir + " && verilator --lint-only -Wall -Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM -Wno-DECLFILENAME --top-module board_top dut.sv board_top.sv"
            : "cd " + dir + " && sudo docker run --rm --cpuset-cpus=16-31 -v " + dir + ":/w -w /w kkrizka/vivado:2020.1 bash -c "
              + "'source /opt/Xilinx/Vivado/2020.1/settings64.sh >/dev/null; vivado -mode batch -nojournal -nolog -source build.tcl > vivado.log 2>&1'; sudo chown -R $(id -u):$(id -g) " + dir
              + "; grep -E \"WNS|TERN_BUILD_DONE|ERROR\" " + dir + "/vivado.log | head";
        Process p = new ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start();
        System.out.print(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        p.waitFor(7200, TimeUnit.SECONDS);
        System.out.println("BOARDBUILD " + name + " " + enc + " exit=" + p.exitValue());
    }
}
