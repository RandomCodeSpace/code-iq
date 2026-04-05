package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.grammar.python.Python3Parser;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Abstract base for Python ANTLR-based detectors.
 * Provides shared {@link #parse(DetectorContext)} with large-file regex fallback,
 * language support declaration, and Python-specific AST helpers used across
 * multiple Python detectors.
 */
public abstract class AbstractPythonAntlrDetector extends AbstractAntlrDetector {

    /** Matches the start of the next class definition — used to bound class bodies in regex fallback. */
    protected static final Pattern NEXT_CLASS_RE = Pattern.compile("\\nclass\\s+\\w+");

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("python");
    }

    @Override
    protected ParseTree parse(DetectorContext ctx) {
        // Size guard is centralized in AntlrParserFactory.parse() (200KB limit)
        return AntlrParserFactory.parse("python", ctx.content());
    }

    /**
     * Build a comma-separated string of base class names from an ANTLR class definition context.
     *
     * @param classCtx the parsed class definition
     * @return base class text, or null if no base classes
     */
    protected static String getBaseClassesText(Python3Parser.ClassdefContext classCtx) {
        if (classCtx.arglist() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (var arg : classCtx.arglist().argument()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(arg.getText());
        }
        return sb.toString();
    }

    /**
     * Extract the source text of an entire class body using ANTLR token positions.
     *
     * @param text     full source content
     * @param classCtx the parsed class definition
     * @return substring covering the full class body
     */
    protected static String extractClassBody(String text, Python3Parser.ClassdefContext classCtx) {
        int start = classCtx.getStart().getStartIndex();
        int stop = classCtx.getStop() != null ? classCtx.getStop().getStopIndex() + 1 : text.length();
        return text.substring(Math.min(start, text.length()), Math.min(stop, text.length()));
    }
}
