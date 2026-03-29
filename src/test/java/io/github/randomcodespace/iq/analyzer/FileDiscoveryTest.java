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
}
