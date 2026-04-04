package io.github.randomcodespace.iq.intelligence.lexical;

import io.github.randomcodespace.iq.intelligence.Provenance;
import io.github.randomcodespace.iq.model.CodeNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Extracts bounded code snippets from source files for a given {@link CodeNode}.
 *
 * <p>Snippets are bounded to at most {@value #MAX_LINES} lines to keep evidence
 * packs compact.
 */
@Component
public class SnippetStore {
    private static final String PROP_UNKNOWN = "unknown";


    /** Maximum lines in any extracted snippet. */
    public static final int MAX_LINES = 50;

    private static final int DEFAULT_CONTEXT_LINES = 3;

    /**
     * Extract a code snippet for the given node using default context (±{@value #DEFAULT_CONTEXT_LINES} lines).
     *
     * @param node     Source node; must have {@code filePath} and {@code lineStart}.
     * @param rootPath Absolute root path of the repository being analysed.
     * @return Snippet, or empty if the node has no location or the file cannot be read.
     */
    public Optional<CodeSnippet> extract(CodeNode node, Path rootPath) {
        return extract(node, rootPath, DEFAULT_CONTEXT_LINES);
    }

    /**
     * Extract a code snippet for the given node with custom context lines.
     *
     * @param node         Source node.
     * @param rootPath     Absolute repository root.
     * @param contextLines Lines of context to add above and below the symbol range.
     * @return Snippet, or empty if node has no location or the file cannot be read.
     */
    public Optional<CodeSnippet> extract(CodeNode node, Path rootPath, int contextLines) {
        if (node.getFilePath() == null || node.getLineStart() == null) return Optional.empty();
        try {
            Path file = rootPath.resolve(node.getFilePath()).normalize();
            if (!file.startsWith(rootPath)) return Optional.empty(); // path traversal guard
            if (!Files.isRegularFile(file)) return Optional.empty();

            String content = Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            int totalLines = lines.length;

            int symStart = node.getLineStart();
            int symEnd = node.getLineEnd() != null ? node.getLineEnd() : symStart;

            // Compute extraction window with context, bounded by file length
            int extractStart = Math.max(1, symStart - contextLines);
            int extractEnd = Math.min(totalLines, symEnd + contextLines);

            // Enforce MAX_LINES cap — centre on the symbol definition
            if (extractEnd - extractStart + 1 > MAX_LINES) {
                int centre = (symStart + symEnd) / 2;
                extractStart = Math.max(1, centre - MAX_LINES / 2);
                extractEnd = Math.min(totalLines, extractStart + MAX_LINES - 1);
            }

            // Build source text (lines are 1-based, array is 0-based)
            var sb = new StringBuilder();
            for (int i = extractStart - 1; i < extractEnd; i++) {
                sb.append(lines[i]).append('\n');
            }

            String language = inferLanguage(node.getFilePath());
            Provenance provenance = Provenance.fromProperties(node.getProperties());
            return Optional.of(new CodeSnippet(sb.toString(), node.getFilePath(),
                    extractStart, extractEnd, language, provenance));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static String inferLanguage(String filePath) {
        if (filePath == null) return PROP_UNKNOWN;
        int dot = filePath.lastIndexOf('.');
        if (dot < 0) return PROP_UNKNOWN;
        return switch (filePath.substring(dot + 1).toLowerCase()) {
            case "java"              -> "java";
            case "ts", "tsx"         -> "typescript";
            case "js", "jsx"         -> "javascript";
            case "py"                -> "python";
            case "go"                -> "go";
            case "rs"                -> "rust";
            case "cs"                -> "csharp";
            case "cpp", "cc", "cxx",
                 "h", "hpp"          -> "cpp";
            case "kt"                -> "kotlin";
            case "scala", "sc"       -> "scala";
            default                  -> PROP_UNKNOWN;
        };
    }
}
