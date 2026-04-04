package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileDiscoveryTest {

    @TempDir
    Path tempDir;

    private FileDiscovery discovery;

    @BeforeEach
    void setUp() {
        discovery = new FileDiscovery(new CodeIqConfig());
    }

    @Test
    void discoversJavaFiles() throws IOException {
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("App.java"), "public class App {}");
        Files.writeString(srcDir.resolve("Service.java"), "public class Service {}");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(2, files.size());
        assertTrue(files.stream().allMatch(f -> "java".equals(f.language())));
    }

    @Test
    void discoversMultipleLanguages() throws IOException {
        Files.writeString(tempDir.resolve("app.py"), "print('hello')");
        Files.writeString(tempDir.resolve("config.yaml"), "key: value");
        Files.writeString(tempDir.resolve("index.ts"), "export const x = 1;");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(3, files.size());
        assertTrue(files.stream().anyMatch(f -> "python".equals(f.language())));
        assertTrue(files.stream().anyMatch(f -> "yaml".equals(f.language())));
        assertTrue(files.stream().anyMatch(f -> "typescript".equals(f.language())));
    }

    @Test
    void excludesNodeModules() throws IOException {
        Path nodeModules = tempDir.resolve("node_modules/some-pkg");
        Files.createDirectories(nodeModules);
        Files.writeString(nodeModules.resolve("index.js"), "module.exports = {}");
        Files.writeString(tempDir.resolve("app.js"), "const x = 1;");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("app.js", files.getFirst().path().toString());
    }

    @Test
    void excludesBuildDirectories() throws IOException {
        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(buildDir);
        Files.writeString(buildDir.resolve("output.java"), "class Output {}");
        Path targetDir = tempDir.resolve("target");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("output.java"), "class Target {}");
        Files.writeString(tempDir.resolve("src.java"), "class Src {}");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("src.java", files.getFirst().path().toString());
    }

    @Test
    void skipsUnrecognizedExtensions() throws IOException {
        Files.writeString(tempDir.resolve("readme.txt"), "hello");
        Files.writeString(tempDir.resolve("data.bin"), "binary");
        Files.writeString(tempDir.resolve("app.java"), "class App {}");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("java", files.getFirst().language());
    }

    @Test
    void recordsFileSize() throws IOException {
        String content = "public class App {}";
        Files.writeString(tempDir.resolve("App.java"), content);

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertTrue(files.getFirst().sizeBytes() > 0);
    }

    @Test
    void resultIsDeterministicallySorted() throws IOException {
        Files.writeString(tempDir.resolve("z.java"), "class Z {}");
        Files.writeString(tempDir.resolve("a.java"), "class A {}");
        Files.writeString(tempDir.resolve("m.java"), "class M {}");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(3, files.size());
        assertEquals("a.java", files.get(0).path().toString());
        assertEquals("m.java", files.get(1).path().toString());
        assertEquals("z.java", files.get(2).path().toString());
    }

    @Test
    void emptyDirectoryReturnsEmptyList() {
        List<DiscoveredFile> files = discovery.discover(tempDir);
        assertTrue(files.isEmpty());
    }

    @Test
    void discoversDockerfile() throws IOException {
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM ubuntu:latest");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("dockerfile", files.getFirst().language());
    }

    @Test
    void discoversMakefile() throws IOException {
        Files.writeString(tempDir.resolve("Makefile"), "all: build");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("makefile", files.getFirst().language());
    }

    @Test
    void excludesLockFiles() throws IOException {
        Files.writeString(tempDir.resolve("package-lock.json"), "{}");
        Files.writeString(tempDir.resolve("yarn.lock"), "# yarn lockfile");
        Files.writeString(tempDir.resolve("pnpm-lock.yaml"), "lockfileVersion: 5.4");
        Files.writeString(tempDir.resolve("go.sum"), "github.com/foo v1.0.0 h1:abc");
        Files.writeString(tempDir.resolve("Cargo.lock"), "[package]");
        Files.writeString(tempDir.resolve("app.java"), "class App {}");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("java", files.getFirst().language());
    }

    @Test
    void excludesVcsDirs() throws IOException {
        Path gitDir = tempDir.resolve(".git/objects");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("packed-refs.java"), "class Foo {}");
        Files.writeString(tempDir.resolve("main.java"), "class Main {}");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("main.java", files.getFirst().path().toString());
    }

    @Test
    void excludesPythonCacheDir() throws IOException {
        Path cacheDir = tempDir.resolve("__pycache__");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("module.py"), "x = 1");
        Files.writeString(tempDir.resolve("app.py"), "print('hi')");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("python", files.getFirst().language());
    }

    @Test
    void skipsOversizedSourceFiles() throws IOException {
        // Create a file larger than 512KB
        byte[] bigContent = new byte[600_000];
        java.util.Arrays.fill(bigContent, (byte) 'x');
        Files.write(tempDir.resolve("BigFile.java"), bigContent);
        Files.writeString(tempDir.resolve("Small.java"), "class Small {}");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("Small.java", files.getFirst().path().toString());
    }

    @Test
    void skipsOversizedConfigFiles() throws IOException {
        // Config files (yaml) capped at 64KB
        byte[] bigYaml = new byte[70_000];
        java.util.Arrays.fill(bigYaml, (byte) 'x');
        Files.write(tempDir.resolve("big.yaml"), bigYaml);
        Files.writeString(tempDir.resolve("small.yaml"), "key: value");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("small.yaml", files.getFirst().path().toString());
    }

    @Test
    void excludesCodeIqOwnDirs() throws IOException {
        Path codeIntelDir = tempDir.resolve(".code-intelligence");
        Path osscodeiqDir = tempDir.resolve(".osscodeiq");
        Files.createDirectories(codeIntelDir);
        Files.createDirectories(osscodeiqDir);
        Files.writeString(codeIntelDir.resolve("cache.java"), "class Cache {}");
        Files.writeString(osscodeiqDir.resolve("meta.java"), "class Meta {}");
        Files.writeString(tempDir.resolve("src.java"), "class Src {}");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("src.java", files.getFirst().path().toString());
    }

    @Test
    void pathComponentExclusionWorksForNestedDirs() throws IOException {
        // A file in path containing "vendor" segment
        Path vendorDir = tempDir.resolve("pkg/vendor/dep");
        Files.createDirectories(vendorDir);
        Files.writeString(vendorDir.resolve("lib.go"), "package main");
        Files.writeString(tempDir.resolve("main.go"), "package main");

        List<DiscoveredFile> files = discovery.discover(tempDir);

        assertEquals(1, files.size());
        assertEquals("main.go", files.getFirst().path().toString());
    }
}
