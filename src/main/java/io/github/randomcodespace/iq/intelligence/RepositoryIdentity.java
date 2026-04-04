package io.github.randomcodespace.iq.intelligence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Identity snapshot of a repository at analysis time.
 * Populated from git metadata during the {@code index} command.
 *
 * @param repoUrl        Remote origin URL (null for local-only repos).
 * @param commitSha      Full SHA-1 of HEAD (null if not a git repo).
 * @param branch         Current branch name (null if detached HEAD or not git).
 * @param buildTimestamp When the analysis run started.
 */
public record RepositoryIdentity(
        String repoUrl,
        String commitSha,
        String branch,
        Instant buildTimestamp
) {
    /**
     * Resolve repository identity from a local path using git commands.
     * Fields that cannot be determined are set to null gracefully.
     */
    public static RepositoryIdentity resolve(java.nio.file.Path repoPath) {
        String repoUrl   = runGit(repoPath, "remote", "get-url", "origin");
        String commitSha = runGit(repoPath, "rev-parse", "HEAD");
        String branch    = runGit(repoPath, "rev-parse", "--abbrev-ref", "HEAD");
        // Detached HEAD produces "HEAD" rather than a branch name — normalise to null
        if ("HEAD".equals(branch)) branch = null;
        return new RepositoryIdentity(repoUrl, commitSha, branch, Instant.now());
    }

    /** Returns null on any error. */
    private static String runGit(java.nio.file.Path repoPath, String... args) {
        try {
            var cmd = new java.util.ArrayList<String>();
            cmd.add("git");
            cmd.addAll(java.util.Arrays.asList(args));
            var pb = new ProcessBuilder(cmd)
                    .directory(repoPath.toFile())
                    .redirectErrorStream(true);
            var proc = pb.start();
            try (var is = proc.getInputStream()) {
                String out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
                int exit = proc.waitFor();
                return (exit == 0 && !out.isBlank()) ? out : null;
            } finally {
                proc.destroy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
