package com.clix.effect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded observation for {@code execute_command} (design doc "execute_command" + V1.2 B).
 *
 * <p>Shell commands are black boxes. Git worktrees yield a HEAD/status delta only; non-Git
 * projects observe nothing unless the caller explicitly declared {@code output_paths}.
 * In particular, no inferred full-tree scan, find, mtime guessing or directory walk is ever
 * started when no paths were declared.
 */
public final class CommandEffectProbe {
    @FunctionalInterface
    public interface CommandRunner {
        String run(List<String> command) throws Exception;
    }

    public record Baseline(boolean git, String head, String porcelain, boolean declared,
                           List<String> declaredPaths,
                           Map<String, FileEffectProbe.State> declaredStates,
                           int scanCount, List<String> limitations) { }

    public record Observation(List<String> observedPaths, String effectAudit, String auditScope,
                              List<String> auditLimitations, int scanCount,
                              boolean externalSideEffectsPossible) { }

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(token|api[_-]?key|secret|password|passwd|authorization|bearer|credential)"
                    + "(\\s*[=:]\\s*|\\s+)(\\S+)");
    /** Authorization headers put the credential after the scheme, not after '=' or ':'. */
    private static final Pattern SCHEME_TOKEN = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+([A-Za-z0-9._~+/=-]{6,})");
    private static final Pattern EXTERNAL = Pattern.compile(
            "(?i)\\b(curl|wget|httpie|docker|kubectl|podman|psql|mysql|mariadb|redis-cli|mongosh|"
                    + "ssh|scp|sftp|ftp|telnet|nc|ncat|terraform|aws|gcloud|az|helm|"
                    + "npm publish|pip install|mvn deploy|gradle publish|"
                    + "invoke-webrequest|invoke-restmethod)\\b|\\bgit\\s+push\\b|https?://");
    private static final int MAX_SUMMARY_CHARS = 200;

    private final Path root;
    private final FileEffectProbe files;
    private final CommandRunner runner;

    public CommandEffectProbe(Path root) { this(root, systemRunner()); }

    public CommandEffectProbe(Path root, CommandRunner runner) {
        this.root = java.util.Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.files = new FileEffectProbe(this.root);
        this.runner = java.util.Objects.requireNonNull(runner, "runner");
    }

    public static CommandRunner systemRunner() {
        return command -> {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return output;
        };
    }

    public static String digest(String command) {
        return EffectEvent.sha256(command == null ? "" : command);
    }

    /** Redacted, truncated command summary; never store the raw command that may contain keys. */
    public static String summary(String command) {
        if (command == null) return "";
        String safe = SCHEME_TOKEN.matcher(command).replaceAll("$1 ***");
        safe = SENSITIVE.matcher(safe).replaceAll("$1$2***");
        safe = safe.replaceAll("\\s+", " ").trim();
        return safe.length() > MAX_SUMMARY_CHARS ? safe.substring(0, MAX_SUMMARY_CHARS) + "…" : safe;
    }

    public static boolean externalSideEffectsPossible(String command) {
        return command != null && EXTERNAL.matcher(command).find();
    }

    /** Captures the observation baseline. No scan is performed without declared paths. */
    public Baseline before(String command, List<String> outputPaths) {
        List<String> limitations = new ArrayList<>();
        List<String> declaredPaths = new ArrayList<>();
        Map<String, FileEffectProbe.State> states = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        int scans = 0;
        if (outputPaths != null) {
            for (String raw : outputPaths) {
                if (raw == null || raw.isBlank() || !seen.add(raw)) continue;
                try {
                    Path resolved = files.resolve(raw);
                    declaredPaths.add(raw);
                    states.put(raw, files.capture(resolved));
                    scans++;
                } catch (FileEffectProbe.PathRejected e) {
                    limitations.add("declared_path_rejected:" + raw);
                }
            }
        }
        boolean declared = outputPaths != null && !outputPaths.isEmpty();
        boolean git = Files.isDirectory(root.resolve(".git"));
        String head = "";
        String porcelain = "";
        if (git) {
            try {
                head = runner.run(List.of("git", "-C", root.toString(), "rev-parse", "HEAD"));
                porcelain = runner.run(List.of("git", "-C", root.toString(), "status", "--porcelain"));
                if (head == null) head = "";
                if (porcelain == null) porcelain = "";
            } catch (Exception e) {
                git = false;
                limitations.add("git_baseline_unavailable");
            }
        }
        limitations.add("shell_black_box");
        limitations.add("external_side_effects_not_observable");
        return new Baseline(git, head.trim(), porcelain, declared, declaredPaths, states, scans, limitations);
    }

    /** Re-observes only the same bounded scope and derives the audit grade. */
    public Observation after(Baseline before, String command) {
        boolean external = externalSideEffectsPossible(command);
        List<String> limitations = new ArrayList<>(before.limitations());
        int scans = before.scanCount();

        if (!before.git() && !before.declared()) {
            limitations.add("no_observation_performed");
            limitations.add("unobserved_file_changes_manual_check_required");
            return new Observation(List.of(), "unavailable", "none", limitations, 0, external);
        }

        List<String> observed = new ArrayList<>();
        if (before.git()) {
            String head = "";
            String porcelain = "";
            try {
                head = runner.run(List.of("git", "-C", root.toString(), "rev-parse", "HEAD"));
                porcelain = runner.run(List.of("git", "-C", root.toString(), "status", "--porcelain"));
                if (head == null) head = "";
                if (porcelain == null) porcelain = "";
            } catch (Exception e) {
                limitations.add("git_result_unavailable");
            }
            Set<String> beforePaths = porcelainPaths(before.porcelain());
            Set<String> afterPaths = porcelainPaths(porcelain);
            Set<String> delta = new LinkedHashSet<>(afterPaths);
            delta.removeAll(beforePaths);
            observed.addAll(delta);
            if (!head.trim().equals(before.head())) limitations.add("git_head_changed");
        }

        boolean declaredComplete = true;
        if (before.declared()) {
            for (String raw : before.declaredPaths()) {
                try {
                    Path resolved = files.resolve(raw);
                    FileEffectProbe.State now = files.capture(resolved);
                    scans++;
                    FileEffectProbe.State was = before.declaredStates().get(raw);
                    if (!now.captured() || was == null || !was.captured()) declaredComplete = false;
                    else if (!java.util.Objects.equals(now.sha256(), was.sha256())
                            || now.size() != was.size()) observed.add(raw);
                } catch (FileEffectProbe.PathRejected e) {
                    declaredComplete = false;
                    limitations.add("declared_path_rejected_after:" + raw);
                }
            }
            limitations.add("declared_paths_only");
            limitations.add("not_a_full_workspace_scan");
        }

        if (before.git() && before.declared()) {
            if (declaredComplete) {
                limitations.add("verified_scope_declared_output_paths_only");
                return new Observation(observed, "verified", "declared_output_paths", limitations, scans, external);
            }
            return new Observation(observed, "partial", "git_worktree_delta_plus_declared_paths",
                    limitations, scans, external);
        }
        if (before.git()) {
            return new Observation(observed, "partial", "git_worktree_delta", limitations, scans, external);
        }
        return new Observation(observed, "partial", "declared_output_paths", limitations, scans, external);
    }

    private static Set<String> porcelainPaths(String porcelain) {
        Set<String> paths = new LinkedHashSet<>();
        if (porcelain == null || porcelain.isBlank()) return paths;
        for (String line : porcelain.split("\\R")) {
            if (line.length() < 4) continue;
            String path = line.substring(3).trim();
            int arrow = path.indexOf(" -> ");
            if (arrow >= 0) path = path.substring(arrow + 4).trim();
            if (!path.isEmpty()) paths.add(path);
        }
        return paths;
    }

    public static String gradeOrUnknown(String value) {
        if (value == null) return "unavailable";
        String normalized = value.toLowerCase(Locale.ROOT);
        return Set.of("verified", "partial", "unavailable").contains(normalized)
                ? normalized : "unavailable";
    }
}
