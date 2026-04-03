package io.github.randomcodespace.iq.intelligence;

/**
 * A single file record in the {@link FileInventory}.
 *
 * @param path           Repository-relative path (forward-slash normalised).
 * @param language       Detected language (lower-case, e.g. "java", "typescript").
 * @param sizeBytes      File size in bytes at discovery time.
 * @param contentHash    SHA-256 hex digest of the file content (may be null for large/skipped files).
 * @param classification Heuristic role of the file.
 */
public record FileEntry(
        String path,
        String language,
        long sizeBytes,
        String contentHash,
        FileClassification classification
) implements Comparable<FileEntry> {

    @Override
    public int compareTo(FileEntry other) {
        return this.path.compareTo(other.path);
    }

    /**
     * Classify a file by its path and language.
     * Rules applied in order — first match wins.
     */
    public static FileClassification classify(String relPath, String language) {
        String lower = relPath.replace('\\', '/').toLowerCase();

        // Generated paths
        if (lower.contains("/generated/") || lower.contains("/target/")
                || lower.contains("/build/") || lower.contains("/dist/")
                || lower.contains("/out/") || lower.contains("/.gradle/")) {
            return FileClassification.GENERATED;
        }
        // Test paths
        if (lower.contains("/test/") || lower.contains("/tests/")
                || lower.contains("/spec/") || lower.contains("/__tests__/")
                || lower.endsWith("test.java") || lower.endsWith("tests.java")
                || lower.endsWith(".test.ts") || lower.endsWith(".spec.ts")
                || lower.endsWith(".test.js") || lower.endsWith(".spec.js")
                || lower.endsWith("_test.go") || lower.endsWith("_test.py")) {
            return FileClassification.TEST;
        }
        // Documentation
        if (lower.contains("/docs/") || lower.contains("/doc/")
                || lower.endsWith(".md") || lower.endsWith(".adoc")
                || lower.endsWith(".rst") || lower.endsWith(".txt")) {
            return FileClassification.DOC;
        }
        // Config by language
        if ("yaml".equals(language) || "json".equals(language) || "toml".equals(language)
                || "ini".equals(language) || "properties".equals(language)
                || "xml".equals(language) || lower.endsWith(".env")) {
            return FileClassification.CONFIG;
        }
        return FileClassification.SOURCE;
    }
}
