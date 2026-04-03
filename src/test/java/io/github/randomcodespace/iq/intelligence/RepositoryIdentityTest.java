package io.github.randomcodespace.iq.intelligence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link RepositoryIdentity}.
 * Validates graceful degradation when git metadata is unavailable.
 */
class RepositoryIdentityTest {

    // ------------------------------------------------------------------
    // Non-git directory — all git fields null, no exception
    // ------------------------------------------------------------------

    @Test
    void resolve_nonGitDirectory_allNullNoException(@TempDir Path dir) {
        assertThatCode(() -> RepositoryIdentity.resolve(dir)).doesNotThrowAnyException();

        RepositoryIdentity id = RepositoryIdentity.resolve(dir);
        assertThat(id.repoUrl()).isNull();
        assertThat(id.commitSha()).isNull();
        assertThat(id.branch()).isNull();
    }

    @Test
    void resolve_nonGitDirectory_buildTimestampAlwaysPresent(@TempDir Path dir) {
        RepositoryIdentity id = RepositoryIdentity.resolve(dir);
        assertThat(id.buildTimestamp()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Git repo with no remote — commitSha present, repoUrl null
    // ------------------------------------------------------------------

    @Test
    void resolve_gitRepoNoRemote_commitShaPopulated(@TempDir Path dir) throws Exception {
        initGitRepo(dir);

        RepositoryIdentity id = RepositoryIdentity.resolve(dir);
        // commit SHA may be null if no commits yet, but should not throw
        assertThat(id.repoUrl()).isNull();
        assertThat(id.buildTimestamp()).isNotNull();
    }

    @Test
    void resolve_gitRepoWithCommit_commitShaPresent(@TempDir Path dir) throws Exception {
        initGitRepo(dir);
        makeInitialCommit(dir);

        RepositoryIdentity id = RepositoryIdentity.resolve(dir);
        assertThat(id.commitSha()).isNotNull().isNotBlank();
        assertThat(id.repoUrl()).isNull();
    }

    // ------------------------------------------------------------------
    // Detached HEAD — branch normalised to null
    // ------------------------------------------------------------------

    @Test
    void resolve_detachedHead_branchIsNull(@TempDir Path dir) throws Exception {
        initGitRepo(dir);
        makeInitialCommit(dir);

        // Detach HEAD by checking out the commit SHA directly
        String sha = runGit(dir, "rev-parse", "HEAD");
        runGit(dir, "checkout", "--detach", sha);

        RepositoryIdentity id = RepositoryIdentity.resolve(dir);
        assertThat(id.branch()).isNull();
        assertThat(id.commitSha()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Record semantics
    // ------------------------------------------------------------------

    @Test
    void record_constructorAndAccessors() {
        var ts = java.time.Instant.now();
        var id = new RepositoryIdentity("https://github.com/x/y", "abc123", "main", ts);

        assertThat(id.repoUrl()).isEqualTo("https://github.com/x/y");
        assertThat(id.commitSha()).isEqualTo("abc123");
        assertThat(id.branch()).isEqualTo("main");
        assertThat(id.buildTimestamp()).isEqualTo(ts);
    }

    @Test
    void record_equalityOnSameValues() {
        var ts = java.time.Instant.parse("2026-01-01T00:00:00Z");
        var id1 = new RepositoryIdentity("url", "sha", "main", ts);
        var id2 = new RepositoryIdentity("url", "sha", "main", ts);
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void record_nullFieldsAllowed() {
        assertThatCode(() -> new RepositoryIdentity(null, null, null, java.time.Instant.now()))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void initGitRepo(Path dir) throws Exception {
        run(dir, "git", "init");
        run(dir, "git", "config", "user.email", "test@test.com");
        run(dir, "git", "config", "user.name", "Test");
    }

    private static void makeInitialCommit(Path dir) throws Exception {
        Path readme = dir.resolve("README.md");
        java.nio.file.Files.writeString(readme, "# Test");
        run(dir, "git", "add", ".");
        run(dir, "git", "commit", "-m", "init");
    }

    private static String runGit(Path dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(java.util.Arrays.asList(args));
        var proc = new ProcessBuilder(cmd).directory(dir.toFile()).start();
        String out = new String(proc.getInputStream().readAllBytes()).trim();
        proc.waitFor();
        return out;
    }

    private static void run(Path dir, String... cmd) throws Exception {
        new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start().waitFor();
    }
}
