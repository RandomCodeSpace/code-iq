package io.github.randomcodespace.iq.intelligence.lexical;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Extracts doc comments from source files by scanning lines before (or just inside)
 * a given declaration.
 *
 * <p>Supported styles:
 * <ul>
 *   <li>Javadoc / JSDoc / C++ Doxygen — {@code /** ... * /} block immediately before the declaration.</li>
 *   <li>Python triple-quoted docstrings — first string literal inside the function/class body.</li>
 *   <li>Go / Rust / TypeScript line comments — contiguous {@code //} lines ending at the declaration.</li>
 * </ul>
 *
 * <p>All methods are static — this class has no state.
 */
public final class DocCommentExtractor {

    private DocCommentExtractor() {}

    /**
     * Extract the doc comment for the symbol declared at {@code lineStart} in {@code file}.
     *
     * @param file      Absolute path to the source file.
     * @param language  Lowercase language identifier (e.g. "java", "typescript", "python").
     * @param lineStart 1-based line number of the symbol declaration.
     * @return Cleaned comment text, or null if none found or file unreadable.
     */
    public static String extract(Path file, String language, int lineStart) {
        if (file == null || language == null || lineStart <= 0) return null;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return extract(lines, language, lineStart);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract the doc comment using pre-read file lines, avoiding redundant file I/O.
     * Used by {@link LexicalEnricher} when processing multiple nodes from the same file.
     *
     * @param lines     All lines of the source file (already read).
     * @param language  Lowercase language identifier (e.g. "java", "typescript", "python").
     * @param lineStart 1-based line number of the symbol declaration.
     * @return Cleaned comment text, or null if none found.
     */
    public static String extract(List<String> lines, String language, int lineStart) {
        if (lines == null || language == null || lineStart <= 0) return null;
        if (lineStart > lines.size()) return null;
        return switch (language) {
            case "python" -> extractPythonDocstring(lines, lineStart);
            case "go", "rust" -> extractLineComments(lines, lineStart);
            default -> extractBlockComment(lines, lineStart);
        };
    }

    /**
     * Extracts a {@code /** ... * /} block comment ending just before the declaration
     * (skipping blank lines and annotation lines).
     */
    private static String extractBlockComment(List<String> lines, int lineStart) {
        // Walk backwards from the declaration line, skip blanks and annotations
        int scanIdx = lineStart - 2; // convert to 0-based, start one line before declaration
        while (scanIdx >= 0) {
            String trimmed = lines.get(scanIdx).trim();
            if (trimmed.isBlank() || trimmed.startsWith("@")) {
                scanIdx--;
                continue;
            }
            break;
        }
        if (scanIdx < 0) return null;

        String endLine = lines.get(scanIdx).trim();
        if (!endLine.endsWith("*/")) return null;

        // Find the matching opening /* or /**
        int openIdx = scanIdx;
        while (openIdx >= 0 && !lines.get(openIdx).trim().startsWith("/*")) {
            openIdx--;
        }
        if (openIdx < 0) return null;

        // Collect and clean the comment block
        var sb = new StringBuilder();
        for (int i = openIdx; i <= scanIdx; i++) {
            String cleaned = lines.get(i).trim()
                    .replaceAll("^/\\*+\\s*", "")
                    .replaceAll("\\s*\\*/$", "")
                    .replaceAll("^\\*\\s?", "")
                    .trim();
            if (!cleaned.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(cleaned);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Extracts contiguous {@code //} line comments immediately before the declaration.
     * Used for Go and Rust doc comment styles.
     */
    private static String extractLineComments(List<String> lines, int lineStart) {
        int scanIdx = lineStart - 2; // 0-based index of line before declaration
        // Skip blank lines
        while (scanIdx >= 0 && lines.get(scanIdx).trim().isBlank()) scanIdx--;

        if (scanIdx < 0) return null;

        // Collect contiguous // lines going upward
        int endIdx = scanIdx;
        while (scanIdx >= 0 && lines.get(scanIdx).trim().startsWith("//")) {
            scanIdx--;
        }
        int startIdx = scanIdx + 1;
        if (startIdx > endIdx) return null;

        var sb = new StringBuilder();
        for (int i = startIdx; i <= endIdx; i++) {
            String cleaned = lines.get(i).trim()
                    .replaceAll("^//[!/]?\\s*", "")
                    .trim();
            if (!cleaned.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(cleaned);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Extracts a Python triple-quoted docstring from the first string literal
     * inside the function/class body (the line immediately after the declaration).
     */
    private static String extractPythonDocstring(List<String> lines, int lineStart) {
        // Python docstring starts at lineStart (0-based: lineStart is the def/class line)
        // The body starts at lineStart (1-based lineStart + 1 = 0-based lineStart)
        StringBuilder accumulated = null;
        String openQuote = null;

        for (int i = lineStart; i < Math.min(lineStart + 15, lines.size()); i++) {
            String line = lines.get(i).trim();
            if (accumulated == null) {
                // Look for opening triple-quote
                int idxDouble = line.indexOf("\"\"\"");
                int idxSingle = line.indexOf("'''");
                int tripleIdx;
                String quote;
                if (idxDouble >= 0 && (idxSingle < 0 || idxDouble <= idxSingle)) {
                    tripleIdx = idxDouble;
                    quote = "\"\"\"";
                } else if (idxSingle >= 0) {
                    tripleIdx = idxSingle;
                    quote = "'''";
                } else {
                    // No triple quote on this line — not a docstring line, stop
                    break;
                }
                openQuote = quote;
                String after = line.substring(tripleIdx + 3);
                int closingIdx = after.indexOf(quote);
                if (closingIdx >= 0) {
                    // Single-line docstring
                    String content = after.substring(0, closingIdx).trim();
                    return content.isBlank() ? null : content;
                }
                accumulated = new StringBuilder(after.trim());
            } else {
                int closingIdx = line.indexOf(openQuote);
                if (closingIdx >= 0) {
                    String before = line.substring(0, closingIdx).trim();
                    if (!before.isBlank()) {
                        if (!accumulated.isEmpty()) accumulated.append(' ');
                        accumulated.append(before);
                    }
                    String result = accumulated.toString().trim();
                    return result.isBlank() ? null : result;
                }
                if (!line.isBlank()) {
                    if (!accumulated.isEmpty()) accumulated.append(' ');
                    accumulated.append(line);
                }
            }
        }
        return null;
    }
}
