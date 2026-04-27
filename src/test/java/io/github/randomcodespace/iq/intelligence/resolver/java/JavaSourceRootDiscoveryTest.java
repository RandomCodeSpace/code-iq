package io.github.randomcodespace.iq.intelligence.resolver.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggressive coverage for {@link JavaSourceRootDiscovery} on synthetic dir
 * layouts. Verifies all 6 plan-mandated scenarios + defensive cases.
 */
class JavaSourceRootDiscoveryTest {

    private final JavaSourceRootDiscovery discovery = new JavaSourceRootDiscovery();

    // ---------- Maven layouts ----------

    @Test
    void mavenSingleModuleReturnsMainAndTestJava(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.createDirectories(tmp.resolve("src/test/java"));
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        List<Path> roots = discovery.discover(tmp);

        assertEquals(2, roots.size());
        assertEquals(tmp.resolve("src/main/java"), roots.get(0));
        assertEquals(tmp.resolve("src/test/java"), roots.get(1));
    }

    @Test
    void mavenSingleModuleMainOnlyReturnsMainOnly(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        List<Path> roots = discovery.discover(tmp);

        assertEquals(List.of(tmp.resolve("src/main/java")), roots);
    }

    @Test
    void mavenMultiModuleAggregatesAllSubmodules(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Files.createDirectories(tmp.resolve("service-a/src/main/java"));
        Files.createDirectories(tmp.resolve("service-a/src/test/java"));
        Files.createDirectories(tmp.resolve("service-b/src/main/java"));

        List<Path> roots = discovery.discover(tmp);

        assertEquals(3, roots.size());
        // Sorted alphabetically: service-a/src/main/java, service-a/src/test/java, service-b/src/main/java
        assertEquals(tmp.resolve("service-a/src/main/java"), roots.get(0));
        assertEquals(tmp.resolve("service-a/src/test/java"), roots.get(1));
        assertEquals(tmp.resolve("service-b/src/main/java"), roots.get(2));
    }

    // ---------- Gradle layouts ----------

    @Test
    void gradleLayoutDetectedSameAsMaven(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.createDirectories(tmp.resolve("src/test/java"));
        // Gradle Kotlin DSL marker
        Files.writeString(tmp.resolve("build.gradle.kts"), "plugins {}");

        List<Path> roots = discovery.discover(tmp);

        // The discovery doesn't actually inspect build files — it walks for src/(main|test)/java.
        // Documents that Maven and Gradle are indistinguishable to this discovery.
        assertEquals(2, roots.size());
        assertEquals(tmp.resolve("src/main/java"), roots.get(0));
        assertEquals(tmp.resolve("src/test/java"), roots.get(1));
    }

    // ---------- Plain layout ----------

    @Test
    void plainSrcWithJavaFileFallsBackToSrcAsRoot(@TempDir Path tmp) throws Exception {
        // No Maven/Gradle markers, no src/main/java — but src/ has a .java file.
        // Fallback: treat src/ as the root.
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Foo.java"), "class Foo {}");

        List<Path> roots = discovery.discover(tmp);

        assertEquals(List.of(tmp.resolve("src")), roots);
    }

    @Test
    void plainSrcWithoutJavaFilesReturnsEmpty(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/README.md"), "# nothing to see here");

        List<Path> roots = discovery.discover(tmp);

        assertTrue(roots.isEmpty(),
                "src/ exists but has no .java files — discovery returns nothing");
    }

    // ---------- Empty / missing ----------

    @Test
    void emptyDirectoryReturnsEmpty(@TempDir Path tmp) {
        List<Path> roots = discovery.discover(tmp);
        assertTrue(roots.isEmpty());
    }

    @Test
    void nonExistentPathReturnsEmpty(@TempDir Path tmp) {
        List<Path> roots = discovery.discover(tmp.resolve("does-not-exist"));
        assertTrue(roots.isEmpty(),
                "missing project root yields empty list, no exception");
    }

    @Test
    void nullPathReturnsEmpty() {
        List<Path> roots = discovery.discover(null);
        assertTrue(roots.isEmpty(),
                "null project root yields empty list, no NPE");
    }

    @Test
    void filePathInsteadOfDirReturnsEmpty(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("not-a-dir.txt"), "hello");
        List<Path> roots = discovery.discover(file);
        assertTrue(roots.isEmpty(),
                "a file (not a directory) yields empty list, no exception");
    }

    // ---------- Skip directories ----------

    @Test
    void targetDirIsSkipped(@TempDir Path tmp) throws Exception {
        // Maven build output — nested src/main/java inside target/ should be ignored.
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.createDirectories(tmp.resolve("target/foo/src/main/java"));
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        List<Path> roots = discovery.discover(tmp);

        assertEquals(List.of(tmp.resolve("src/main/java")), roots,
                "target/ is skipped — its phantom src/main/java is not a real source root");
    }

    @Test
    void buildAndNodeModulesSkipped(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.createDirectories(tmp.resolve("build/classes/main/src/main/java"));
        Files.createDirectories(tmp.resolve("node_modules/some-pkg/src/main/java"));

        List<Path> roots = discovery.discover(tmp);

        assertEquals(List.of(tmp.resolve("src/main/java")), roots,
                "build/ and node_modules/ are skipped — their phantom src trees are not roots");
    }

    @Test
    void dotGitIsSkipped(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.createDirectories(tmp.resolve(".git/objects"));
        Files.createDirectories(tmp.resolve(".gradle/caches"));
        Files.createDirectories(tmp.resolve(".idea/workspace"));

        List<Path> roots = discovery.discover(tmp);

        assertEquals(List.of(tmp.resolve("src/main/java")), roots);
    }

    // ---------- Determinism + safety ----------

    @Test
    void resultsAreSortedAlphabetically(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("zzz/src/main/java"));
        Files.createDirectories(tmp.resolve("aaa/src/main/java"));
        Files.createDirectories(tmp.resolve("mmm/src/main/java"));

        List<Path> roots = discovery.discover(tmp);

        assertEquals(3, roots.size());
        assertEquals(tmp.resolve("aaa/src/main/java"), roots.get(0));
        assertEquals(tmp.resolve("mmm/src/main/java"), roots.get(1));
        assertEquals(tmp.resolve("zzz/src/main/java"), roots.get(2));
    }

    @Test
    void discoveryIsIdempotent(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.createDirectories(tmp.resolve("src/test/java"));
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        List<Path> first = discovery.discover(tmp);
        List<Path> second = discovery.discover(tmp);

        assertEquals(first, second,
                "two calls over the same tree return identical results — determinism");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // symlink semantics differ on Windows
    void symlinkLoopTerminatesWithoutException(@TempDir Path tmp) throws Exception {
        // Create a real source root and a symlink loop pointing back at the project root.
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Files.createSymbolicLink(tmp.resolve("loop-link"), tmp);

        // Files.walkFileTree with NOFOLLOW_LINKS doesn't traverse symlinks → no cycle.
        List<Path> roots = assertDoesNotThrow(() -> discovery.discover(tmp));
        assertEquals(List.of(tmp.resolve("src/main/java")), roots,
                "symlink loop does not cause infinite recursion or duplicate detection");
    }

    @Test
    void srcMainJavaWithDeepNestingStillFound(@TempDir Path tmp) throws Exception {
        // Deeply nested module — verifies walkFileTree doesn't hit a depth limit.
        Path deep = tmp.resolve("a/b/c/d/e/service/src/main/java");
        Files.createDirectories(deep);

        List<Path> roots = discovery.discover(tmp);

        assertEquals(List.of(deep), roots);
    }

    @Test
    void srcMainKotlinIsNotMistakenForJava(@TempDir Path tmp) throws Exception {
        // The check is for the literal "java" leaf — Kotlin sources at
        // src/main/kotlin must NOT be reported as a Java source root.
        Files.createDirectories(tmp.resolve("src/main/kotlin"));
        Files.createDirectories(tmp.resolve("src/test/kotlin"));

        List<Path> roots = discovery.discover(tmp);

        assertTrue(roots.isEmpty(),
                "src/main/kotlin is not a Java source root");
    }
}
