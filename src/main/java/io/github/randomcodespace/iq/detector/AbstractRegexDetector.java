package io.github.randomcodespace.iq.detector;

import io.github.randomcodespace.iq.model.Confidence;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for regex-based detectors.
 * Provides common utilities for line-based content processing.
 */
public abstract class AbstractRegexDetector implements Detector {

    /**
     * Regex matches are pattern-only — no parse tree, no symbol resolution.
     * Confidence floor for emissions from this base class is {@link Confidence#LEXICAL}.
     */
    @Override
    public Confidence defaultConfidence() {
        return Confidence.LEXICAL;
    }

    /**
     * A single line of content with its 1-based line number.
     */
    public record IndexedLine(int lineNumber, String text) {}

    /**
     * Split content into indexed lines with 1-based line numbers.
     */
    protected List<IndexedLine> iterLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        String[] lines = content.split("\n", -1);
        List<IndexedLine> result = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            result.add(new IndexedLine(i + 1, lines[i]));
        }
        return result;
    }

    /**
     * Find the 1-based line number for a character offset in the content.
     */
    protected int findLineNumber(String content, int charOffset) {
        if (content == null || charOffset < 0) {
            return 1;
        }
        int clamped = Math.min(charOffset, content.length());
        int line = 1;
        for (int i = 0; i < clamped; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Extract just the filename from the file path in the context.
     */
    protected String fileName(DetectorContext ctx) {
        String path = ctx.filePath();
        if (path == null || path.isEmpty()) {
            return "";
        }
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * Check if the filename in the context matches any of the given glob-like patterns.
     * Supports simple patterns: '*' matches any sequence, '?' matches a single char.
     */
    protected boolean matchesFilename(DetectorContext ctx, String... patterns) {
        String name = fileName(ctx);
        for (String pattern : patterns) {
            if (globMatch(pattern, name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatch(String pattern, String text) {
        // Convert glob to regex: escape regex chars, then replace glob wildcards
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return text.matches(regex);
    }
}
