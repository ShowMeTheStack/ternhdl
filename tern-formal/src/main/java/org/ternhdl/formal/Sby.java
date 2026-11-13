package org.ternhdl.formal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs SymbiYosys on a set of SV files. */
public final class Sby {
    public enum Status { PASS, FAIL, ERROR, TIMEOUT }
    public record Result(Status status, double seconds, String logTail, Path dir) { }

    private Sby() { }

    public static Path workRoot() {
        String w = System.getenv("TERN_WORK");
        return Path.of(w != null ? w : System.getProperty("java.io.tmpdir"), "formal");
    }

    public static Result prove(Path dir, Map<String, String> files, String top, String solver, int depth, int timeoutSec) throws IOException, InterruptedException {
        return prove(dir, files, top, solver, depth, timeoutSec, "prove");
    }

    public static Result prove(Path dir, Map<String, String> files, String top, String solver, int depth, int timeoutSec, String mode) throws IOException, InterruptedException {
        if (Files.exists(dir)) try (var st = Files.walk(dir)) { st.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        Files.createDirectories(dir);
        StringBuilder read = new StringBuilder(), list = new StringBuilder();
        for (var f : files.entrySet()) {
            Files.writeString(dir.resolve(f.getKey()), f.getValue());
            read.append("read -formal ").append(f.getKey()).append('\n');
            list.append(f.getKey()).append('\n');
        }
        String cfg = "[options]\nmode " + mode + "\ndepth " + depth + "\naigsmt z3\n\n[engines]\n" + (solver.contains(" ") ? solver : "smtbmc " + solver) + "\n\n[script]\n" + read
            + "hierarchy -top " + top + "\nproc -norom\nprep -top " + top + "\n\n[files]\n" + list;
        Files.writeString(dir.resolve("job.sby"), cfg);
        String sby = System.getenv("SBY");
        if (sby == null) throw new IllegalStateException("SBY not set; source env.sh");
        List<String> cmd = new ArrayList<>(Arrays.asList(sby.trim().split("\\s+")));
        cmd.addAll(List.of("-f", "job.sby"));
        long t0 = System.nanoTime();
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        StringBuilder log = new StringBuilder();
        Thread rd = new Thread(() -> { try { log.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)); } catch (IOException ignored) { } });
        rd.start();
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); }
        rd.join(5000);
        double sec = (System.nanoTime() - t0) / 1e9;
        Files.writeString(dir.resolve("run.log"), log.toString());
        String l = log.toString();
        String tail = l.length() > 3000 ? l.substring(l.length() - 3000) : l;
        Status st = !done ? Status.TIMEOUT : l.contains("DONE (PASS") ? Status.PASS
            : (l.contains("DONE (FAIL") || l.matches("(?s).*engine_\\d+ \\([^)]*\\) returned FAIL.*")) ? Status.FAIL : Status.ERROR;
        return new Result(st, sec, tail, dir);
    }
}
