package io.github.randomcodespace.iq.deploy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sub-project 2 — sentinel test for {@code scripts/aks-launch.sh}.
 *
 * <p>The script encodes the JVM flag preset that lets {@code codeiq serve}
 * run inside an AKS pod with {@code securityContext.readOnlyRootFilesystem=true}
 * and {@code /tmp} mounted writable. The preset is the deploy contract — losing
 * any flag here means the next deploy fails with a "read-only file system"
 * error at startup. This test asserts every flag is present so flag drift
 * during refactors fails CI, not production.
 *
 * <p>The test is deliberately a string-grep against the script source rather
 * than an exec — the runbook's docker smoke
 * ({@code shared/runbooks/aks-read-only-deploy.md} §5.1) is the SSoT for
 * "did the deploy assumption actually hold." The unit test only catches
 * drift in the file we control.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AksLaunchScriptSentinelTest {

    private static final Path SCRIPT_PATH = Path.of("scripts/aks-launch.sh");

    private String script;

    @BeforeAll
    void loadScript() throws IOException {
        assertTrue(Files.exists(SCRIPT_PATH),
                "scripts/aks-launch.sh missing — sub-project 2 deploy contract is broken");
        script = Files.readString(SCRIPT_PATH);
    }

    @Test
    void scriptIsExecutable() {
        // Posix permissions check via file attribute. On non-Posix runners
        // (e.g. Windows CI), Files.isExecutable is the best we can do.
        assertTrue(Files.isExecutable(SCRIPT_PATH),
                "aks-launch.sh must be chmod +x — runbook installs it at "
                        + "/usr/local/bin/aks-launch.sh and the container ENTRYPOINT exec's it");
    }

    @Test
    void scriptUsesStrictBashMode() {
        assertTrue(script.contains("set -euo pipefail"),
                "aks-launch.sh must use 'set -euo pipefail' — silent failures in init "
                        + "(e.g. /tmp pre-flight check) would let the JVM start in a broken state");
    }

    @Test
    void scriptValidatesArgCount() {
        assertTrue(script.contains("$# -ne 1"),
                "aks-launch.sh must reject any argv shape other than exactly one data-dir arg");
    }

    @Test
    void scriptSetsSpringBootLoaderTmpDir() {
        // Without this, Spring Boot extracts nested JARs to ~/.m2/spring-boot-loader-tmp/
        // — outside /tmp, fails under read-only HOME.
        assertTrue(script.contains("-Dorg.springframework.boot.loader.tmpDir=/tmp/spring-boot-loader"),
                "spring-boot-loader tmpDir must be redirected to /tmp/spring-boot-loader");
    }

    @Test
    void scriptSetsJavaIoTmpdir() {
        // Explicit even though /tmp is the Linux default — multipart upload
        // temps, JNA, Netty native-lib extraction all use this; making it
        // explicit means base-image-default drift can't break us.
        assertTrue(script.contains("-Djava.io.tmpdir=/tmp"),
                "java.io.tmpdir must be explicitly /tmp");
    }

    @Test
    void scriptSetsJvmErrorFile() {
        // Default: cwd. cwd under read-only root = unwritable. JVM crash
        // would silently drop the dump file. Setting this captures crashes
        // in /tmp where ops can extract them via kubectl cp.
        assertTrue(script.contains("-XX:ErrorFile=/tmp/hs_err_pid%p.log"),
                "JVM ErrorFile must land in /tmp so crash dumps survive a read-only root");
    }

    @Test
    void scriptSetsHeapDumpPath() {
        assertTrue(script.contains("-XX:HeapDumpPath=/tmp"),
                "Heap dump path must be /tmp (cwd is read-only)");
    }

    @Test
    void scriptEnablesHeapDumpOnOom() {
        // Without this, HeapDumpPath is inert.
        assertTrue(script.contains("-XX:+HeapDumpOnOutOfMemoryError"),
                "+HeapDumpOnOutOfMemoryError must be on so the configured path actually fires");
    }

    @Test
    void scriptExecsJavaAsPid1() {
        // exec (not just call) lets SIGTERM from kubelet reach the JVM
        // directly on pod shutdown — without it, bash sits between as PID 1
        // and the JVM doesn't get the signal until bash propagates (or
        // doesn't, depending on shell traps).
        assertTrue(script.contains("exec java"),
                "must `exec java` so the JVM is PID 1 and receives SIGTERM directly on pod stop");
    }

    @Test
    void scriptDoesPreflightTmpCheck() {
        // The 1 GB floor is documented in the spec. Pre-flighting catches
        // a misconfigured emptyDir.sizeLimit before Neo4j blows up halfway
        // through opening its store.
        assertTrue(script.contains("/tmp") && script.contains("df -Pk"),
                "must pre-flight /tmp free space via 'df -Pk /tmp'");
        assertTrue(script.contains("1048576"),
                "the 1 GB minimum (1048576 KB) floor must be enforced — see spec §9 risks");
    }

    @Test
    void scriptCreatesSpringBootLoaderDir() {
        // mkdir is idempotent (-p) — the dir might already exist on a
        // restart-without-pod-recreate path.
        assertTrue(script.contains("mkdir -p /tmp/spring-boot-loader"),
                "must create the spring-boot-loader extraction dir before java starts");
    }
}
