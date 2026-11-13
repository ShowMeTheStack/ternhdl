package org.ternhdl.spice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs ngspice in batch mode and reads wrdata files (time/sweep column followed by value pairs). */
public final class Ngspice {
    private Ngspice() { }

    public static Path workRoot() {
        String w = System.getenv("TERN_WORK");
        return Path.of(w != null ? w : System.getProperty("java.io.tmpdir"), "spice");
    }

    public static String run(Path dir, String deck, int timeoutSec) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("deck.cir"), deck);
        Process p = new ProcessBuilder("ngspice", "-b", "deck.cir").directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("ngspice timeout in " + dir); }
        Files.writeString(dir.resolve("ngspice.log"), out);
        if (out.contains("Error") && !out.contains("Error: measure")) throw new IOException("ngspice error in " + dir + ":\n" + out.substring(0, Math.min(3000, out.length())));
        return out;
    }

    /** Columns of a wrdata file written with "set wr_singlescale": x, then one value per vector. */
    public static double[][] read(Path file, int vectors) throws IOException {
        List<double[]> rows = new ArrayList<>();
        for (String l : Files.readAllLines(file)) {
            String[] t = l.trim().split("\\s+");
            if (t.length < vectors + 1) continue;
            double[] r = new double[vectors + 1];
            try { for (int v = 0; v <= vectors; v++) r[v] = Double.parseDouble(t[v]); } catch (NumberFormatException e) { continue; }
            rows.add(r);
        }
        return rows.toArray(new double[0][]);
    }
}
