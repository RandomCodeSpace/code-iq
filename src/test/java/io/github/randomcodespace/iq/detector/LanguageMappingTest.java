package io.github.randomcodespace.iq.detector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that all 35 language extensions are correctly mapped by DetectorUtils.deriveLanguage().
 */
class LanguageMappingTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "src/Main.java,           java",
        "app.py,                  python",
        "index.ts,                typescript",
        "Component.tsx,           typescript",
        "app.js,                  javascript",
        "Component.jsx,           javascript",
        "config.yaml,             yaml",
        "config.yml,              yaml",
        "data.json,               json",
        "pom.xml,                 xml",
        "main.go,                 go",
        "lib.rs,                  rust",
        "Main.kt,                 kotlin",
        "build.gradle.kts,        kotlin",
        "App.scala,               scala",
        "Program.cs,              csharp",
        "main.cpp,                cpp",
        "util.cc,                 cpp",
        "main.c,                  c",
        "header.h,                c",
        "deploy.sh,               bash",
        "setup.bash,              bash",
        "script.ps1,              powershell",
        "main.tf,                 terraform",
        "config.hcl,              terraform",
        "build.dockerfile,        dockerfile",
        "README.md,               markdown",
        "service.proto,           proto",
        "schema.sql,              sql",
        "build.gradle,            gradle",
        "app.properties,          properties",
        "config.toml,             toml",
        "settings.ini,            ini",
        "App.vue,                 vue",
        "Page.svelte,             svelte"
    })
    void deriveLanguageMapsExtensionCorrectly(String filePath, String expectedLanguage) {
        assertEquals(expectedLanguage, DetectorUtils.deriveLanguage(filePath));
    }

    @Test
    void deriveLanguageReturnsNullForUnrecognizedExtension() {
        assertNull(DetectorUtils.deriveLanguage("file.xyz"));
        assertNull(DetectorUtils.deriveLanguage("noextension"));
    }

    @Test
    void deriveLanguageHandlesNullAndEmpty() {
        assertNull(DetectorUtils.deriveLanguage(null));
        assertNull(DetectorUtils.deriveLanguage(""));
    }

    @Test
    void deriveLanguageHandlesDockerfileWithoutExtension() {
        assertEquals("dockerfile", DetectorUtils.deriveLanguage("Dockerfile"));
        assertEquals("dockerfile", DetectorUtils.deriveLanguage("path/to/Dockerfile"));
    }

    @Test
    void deriveLanguageHandlesPathsWithDirectories() {
        assertEquals("java", DetectorUtils.deriveLanguage("src/main/java/App.java"));
        assertEquals("python", DetectorUtils.deriveLanguage("/home/user/project/script.py"));
    }

    @Test
    void deriveLanguageIsCaseSensitiveForExtension() {
        // Extensions are matched exactly (case-sensitive)
        assertNull(DetectorUtils.deriveLanguage("Main.JAVA"));
        assertNull(DetectorUtils.deriveLanguage("script.PY"));
        // But correct case works
        assertEquals("java", DetectorUtils.deriveLanguage("Main.java"));
        assertEquals("python", DetectorUtils.deriveLanguage("script.py"));
    }

    @Test
    void allThirtyFiveExtensionsAreMapped() {
        // Verify we have exactly 35 extension mappings
        // The 35 extensions: .java, .py, .ts, .tsx, .js, .jsx, .yaml, .yml, .json, .xml,
        // .go, .rs, .kt, .kts, .scala, .cs, .cpp, .cc, .c, .h, .sh, .bash,
        // .ps1, .tf, .hcl, .dockerfile, .md, .proto, .sql, .gradle, .properties,
        // .toml, .ini, .vue, .svelte
        String[] extensions = {
            ".java", ".py", ".ts", ".tsx", ".js", ".jsx", ".yaml", ".yml", ".json", ".xml",
            ".go", ".rs", ".kt", ".kts", ".scala", ".cs", ".cpp", ".cc", ".c", ".h",
            ".sh", ".bash", ".ps1", ".tf", ".hcl", ".dockerfile", ".md", ".proto", ".sql",
            ".gradle", ".properties", ".toml", ".ini", ".vue", ".svelte"
        };
        assertEquals(35, extensions.length);
        for (String ext : extensions) {
            String result = DetectorUtils.deriveLanguage("file" + ext);
            assertNotNull(result,
                "Extension " + ext + " should be mapped but returned null");
        }
    }
}
