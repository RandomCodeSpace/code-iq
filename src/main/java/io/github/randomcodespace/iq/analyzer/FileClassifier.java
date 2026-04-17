package io.github.randomcodespace.iq.analyzer;

import java.nio.file.Path;
import java.util.Set;

/**
 * Classifies files into categories that determine how they are processed
 * in the analysis pipeline.
 * <p>
 * SOURCE and CONFIG files get full detector treatment; TEST, BINARY,
 * GENERATED, and TEXT files get inventory-only nodes (no detectors).
 * MINIFIED is detected via a content heuristic after classification.
 */
public final class FileClassifier {
    private FileClassifier() {}

    public enum FileType {
        SOURCE,     // Code files with architecture keywords -> full detection
        CONFIG,     // YAML, JSON, TOML, INI, properties, Dockerfile, etc.
        TEST,       // Test files -> inventory only, no detectors
        GENERATED,  // .d.ts, .map, .lock, .generated.*, vendor/, generated/
        MINIFIED,   // Detected by isMinified() heuristic
        TEXT,       // Readable but no arch keywords -> inventory + snippet
        BINARY      // Images, fonts, compiled assets -> inventory only
    }

    // Test path patterns
    private static final Set<String> TEST_DIRS = Set.of(
        "test", "tests", "spec", "specs", "__tests__", "__mocks__",
        "testing", "testdata", "fixtures", "test-resources", "testFixtures"
    );

    // Generated directory patterns
    private static final Set<String> GENERATED_DIRS = Set.of(
        "generated", "gen", "vendor", "third_party", "thirdparty"
    );

    // Binary extensions
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
        "png", "jpg", "jpeg", "gif", "bmp", "ico", "svg", "webp",
        "woff", "woff2", "ttf", "eot", "otf",
        "pdf", "zip", "gz", "tar", "jar", "war", "ear",
        "class", "pyc", "pyo", "so", "dll", "exe", "dylib",
        "mp3", "mp4", "wav", "avi", "mov",
        "sqlite", "db", "mdb"
    );

    /**
     * Classify a file based on its path, filename, and language.
     *
     * @param relativePath path relative to repository root
     * @param language     language identifier from FileDiscovery (may be null)
     * @return the file type classification
     */
    public static FileType classify(Path relativePath, String language) {
        String pathStr = relativePath.toString().replace('\\', '/');
        String fileName = java.util.Objects.toString(relativePath.getFileName(), "");
        String ext = getExtension(fileName);

        // Binary check first
        if (BINARY_EXTENSIONS.contains(ext.toLowerCase())) {
            return FileType.BINARY;
        }

        // Generated check
        if (isGenerated(pathStr, fileName, ext)) {
            return FileType.GENERATED;
        }

        // Test check
        if (isTestFile(pathStr, fileName, language)) {
            return FileType.TEST;
        }

        // Config languages (YAML, JSON, TOML, etc.) are handled as CONFIG
        if (isConfigLanguage(language)) {
            return FileType.CONFIG;
        }

        // If it has a known programming language, it's source
        if (language != null && !language.isEmpty()) {
            return FileType.SOURCE;
        }

        // Unknown -> TEXT (will be inventory + snippet)
        return FileType.TEXT;
    }

    static boolean isTestFile(String pathStr, String fileName, String language) {
        // Check path components
        for (String part : pathStr.split("/")) {
            if (TEST_DIRS.contains(part)) return true;
        }
        // Check filename patterns by language
        String lower = fileName.toLowerCase();
        if (lower.endsWith("test.java") || lower.endsWith("tests.java") ||
            lower.endsWith("spec.java") || lower.endsWith("it.java")) return true;
        if (lower.endsWith("_test.go")) return true;
        if (lower.startsWith("test_") && lower.endsWith(".py")) return true;
        if (lower.endsWith("_test.py")) return true;
        if (lower.endsWith(".test.ts") || lower.endsWith(".spec.ts") ||
            lower.endsWith(".test.js") || lower.endsWith(".spec.js") ||
            lower.endsWith(".test.tsx") || lower.endsWith(".spec.tsx") ||
            lower.endsWith(".test.jsx") || lower.endsWith(".spec.jsx")) return true;
        if (lower.endsWith("test.kt") || lower.endsWith("test.scala") ||
            lower.endsWith("spec.scala")) return true;
        if (lower.endsWith("_test.rs")) return true;
        return false;
    }

    static boolean isGenerated(String pathStr, String fileName, String ext) {
        for (String part : pathStr.split("/")) {
            if (GENERATED_DIRS.contains(part)) return true;
        }
        if (fileName.endsWith(".d.ts") || fileName.endsWith(".js.map") ||
            fileName.endsWith(".css.map")) return true;
        if (fileName.endsWith(".generated.java") || fileName.endsWith(".generated.ts") ||
            fileName.contains("_generated")) return true;
        if ("lock".equals(ext) || fileName.equals("package-lock.json") ||
            fileName.equals("yarn.lock") || fileName.equals("pnpm-lock.yaml") ||
            fileName.equals("Cargo.lock") || fileName.equals("poetry.lock") ||
            fileName.equals("Gemfile.lock") || fileName.equals("go.sum")) return true;
        return false;
    }

    static boolean isConfigLanguage(String language) {
        if (language == null) return false;
        return switch (language) {
            case "yaml", "json", "toml", "ini", "properties", "xml",
                 "dockerfile", "hcl", "bicep", "proto", "graphql" -> true;
            default -> false;
        };
    }

    static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }
}
