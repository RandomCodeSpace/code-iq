package io.github.randomcodespace.iq.detector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class DetectorUtilsTest {

    // --- deriveLanguage tests ---

    @ParameterizedTest
    @CsvSource({
            "Foo.java, java",
            "app.py, python",
            "index.ts, typescript",
            "component.tsx, typescript",
            "app.js, javascript",
            "config.yaml, yaml",
            "config.yml, yaml",
            "data.json, json",
            "pom.xml, xml",
            "main.go, go",
            "lib.rs, rust",
            "App.kt, kotlin",
            "Program.cs, csharp",
            "main.tf, terraform",
            "query.sql, sql",
            "schema.graphql, graphql",
            "app.vue, vue",
            "App.svelte, svelte",
            "script.sh, bash",
            "run.ps1, powershell",
            "lib.rb, ruby",
            "Main.scala, scala",
            "App.swift, swift",
            "lib.cpp, cpp",
            "main.c, c",
            "script.pl, perl",
            "main.lua, lua",
            "app.dart, dart",
            "app.dockerfile, dockerfile",
            "config.toml, toml",
            "readme.md, markdown",
            "app.mjs, javascript",
            "app.cjs, javascript",
            "app.mts, typescript",
            "app.cts, typescript",
            "types.pyi, python",
            "schema.gql, graphql",
            "vars.tfvars, terraform",
            "config.hcl, terraform",
            "build.groovy, groovy"
    })
    void deriveLanguageForExtensions(String filename, String expected) {
        assertEquals(expected, DetectorUtils.deriveLanguage(filename));
    }

    @ParameterizedTest
    @CsvSource({
            "style.css",
            "style.scss",
            "style.less",
            "index.html",
            "index.htm",
            "run.bat",
            "run.cmd",
            "script.zsh",
            "settings.ini",
            "app.cfg",
            "app.conf",
            "app.env",
            "data.csv",
            "page.razor",
            "page.cshtml",
            "doc.adoc",
            "settings.jsonc",
            "build.gradle",
            "module.psm1",
            "manifest.psd1"
    })
    void removedExtensionsReturnNull(String filename) {
        assertNull(DetectorUtils.deriveLanguage(filename));
    }

    @ParameterizedTest
    @CsvSource({
            "Dockerfile, dockerfile",
            "Makefile, makefile",
            "GNUmakefile, makefile",
            "Jenkinsfile, groovy",
            "Vagrantfile, ruby",
            "Gemfile, ruby",
            "Rakefile, ruby",
            "go.mod, gomod"
    })
    void deriveLanguageForFilenames(String filename, String expected) {
        assertEquals(expected, DetectorUtils.deriveLanguage(filename));
    }

    @Test
    void goSumIsExcludedReturnsNull() {
        assertNull(DetectorUtils.deriveLanguage("go.sum"));
    }

    @Test
    void deriveLanguageWithPath() {
        assertEquals("java", DetectorUtils.deriveLanguage("src/main/java/com/app/Foo.java"));
    }

    @Test
    void deriveLanguageUnknownExtension() {
        assertNull(DetectorUtils.deriveLanguage("data.xyz"));
    }

    @Test
    void deriveLanguageNullAndEmpty() {
        assertNull(DetectorUtils.deriveLanguage(null));
        assertNull(DetectorUtils.deriveLanguage(""));
    }

    // --- deriveModuleName tests ---

    @Test
    void deriveModuleNameForJava() {
        assertEquals("com.app",
                DetectorUtils.deriveModuleName("src/main/java/com/app/Foo.java", "java"));
    }

    @Test
    void deriveModuleNameForJavaTestSource() {
        assertEquals("com.app.service",
                DetectorUtils.deriveModuleName("src/test/java/com/app/service/FooTest.java", "java"));
    }

    @Test
    void deriveModuleNameForJavaRootPackage() {
        // File directly under src/main/java/ with no package directory
        assertNull(DetectorUtils.deriveModuleName("src/main/java/App.java", "java"));
    }

    @Test
    void deriveModuleNameForJavaNoMarker() {
        assertNull(DetectorUtils.deriveModuleName("lib/Foo.java", "java"));
    }

    @Test
    void deriveModuleNameForPython() {
        assertEquals("src.app.module",
                DetectorUtils.deriveModuleName("src/app/module/foo.py", "python"));
    }

    @Test
    void deriveModuleNameForPythonRootFile() {
        assertNull(DetectorUtils.deriveModuleName("foo.py", "python"));
    }

    @Test
    void deriveModuleNameForStructuredLanguage() {
        assertEquals("config",
                DetectorUtils.deriveModuleName("config/app.yaml", "yaml"));
    }

    @Test
    void deriveModuleNameForStructuredLanguageRootFile() {
        assertNull(DetectorUtils.deriveModuleName("app.yaml", "yaml"));
    }

    @Test
    void deriveModuleNameForUnknownLanguage() {
        assertNull(DetectorUtils.deriveModuleName("src/file.unknown", "unknown"));
    }

    @Test
    void deriveModuleNameNullInputs() {
        assertNull(DetectorUtils.deriveModuleName(null, "java"));
        assertNull(DetectorUtils.deriveModuleName("Foo.java", null));
    }

    // --- decodeContent tests ---

    @Test
    void decodeContentValidUtf8() {
        byte[] raw = "Hello, World!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("Hello, World!", DetectorUtils.decodeContent(raw));
    }

    @Test
    void decodeContentEmptyAndNull() {
        assertEquals("", DetectorUtils.decodeContent(new byte[0]));
        assertEquals("", DetectorUtils.decodeContent(null));
    }

    @Test
    void decodeContentInvalidBytes() {
        // 0xFF is not valid UTF-8; should be replaced, not throw
        byte[] raw = {(byte) 0xFF, (byte) 0xFE, 'A', 'B'};
        String result = DetectorUtils.decodeContent(raw);
        assertNotNull(result);
        assertTrue(result.contains("AB"));
    }
}
