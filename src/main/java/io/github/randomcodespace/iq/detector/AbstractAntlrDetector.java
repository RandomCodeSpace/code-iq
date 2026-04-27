package io.github.randomcodespace.iq.detector;

import io.github.randomcodespace.iq.model.Confidence;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * Abstract base class for ANTLR-based detectors.
 * Provides AST parsing with automatic fallback to regex when parsing fails.
 *
 * <p>Subclasses implement {@link #parse(DetectorContext)} to produce a parse tree
 * for their language, and {@link #detectWithAst(ParseTree, DetectorContext)} to
 * walk the tree and extract nodes/edges. If parsing fails, the detector falls back
 * to {@link #detectWithRegex(DetectorContext)} (which defaults to empty results
 * but can be overridden for hybrid AST+regex detection).</p>
 */
public abstract class AbstractAntlrDetector extends AbstractRegexDetector {

    private static final Logger log = LoggerFactory.getLogger(AbstractAntlrDetector.class);

    /**
     * ANTLR parse trees are syntactic but not symbol-resolved — bump the
     * regex-default {@link Confidence#LEXICAL} up to {@link Confidence#SYNTACTIC}.
     * Subclasses that resolve symbols should call {@code setConfidence(RESOLVED)}
     * explicitly on their emissions.
     */
    @Override
    public Confidence defaultConfidence() {
        return Confidence.SYNTACTIC;
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        try {
            ParseTree tree = parse(ctx);
            if (tree != null) {
                return detectWithAst(tree, ctx);
            }
        } catch (Throwable e) {
            log.warn("ANTLR parse failed for {}, falling back to regex: {}",
                    ctx.filePath(), e.getMessage());
        }
        return detectWithRegex(ctx);
    }

    /**
     * Parse the source content into an ANTLR parse tree.
     * Return null if the language is not supported or content is empty.
     * Default returns null (no parse tree); override for AST-based detection.
     */
    protected ParseTree parse(DetectorContext ctx) {
        return null;
    }

    /**
     * Detect code patterns by walking the ANTLR parse tree.
     * Default delegates to regex fallback; override for AST-based detection.
     */
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        return detectWithRegex(ctx);
    }

    /**
     * Fallback detection using regex when AST parsing fails.
     * Override this for hybrid AST+regex detectors.
     */
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        return DetectorResult.empty();
    }

    /**
     * Run regex-only detection, bypassing ANTLR entirely.
     * Used when ANTLR times out on a file — ensures the file still produces
     * nodes/edges via regex fallback instead of being skipped (zero data loss).
     */
    public DetectorResult detectRegexOnly(DetectorContext ctx) {
        return detectWithRegex(ctx);
    }

    /**
     * Create a lexer from source content with error output suppressed.
     *
     * @param factory function to create the lexer (e.g. {@code MyLexer::new})
     * @param content the source code to lex
     * @return the configured lexer
     */
    protected <L extends Lexer> L createLexer(
            Function<CharStream, L> factory,
            String content) {
        CharStream input = CharStreams.fromString(content);
        L lexer = factory.apply(input);
        lexer.removeErrorListeners();
        return lexer;
    }

    /**
     * Create a parser from a lexer with error output suppressed.
     * Uses SLL prediction mode for speed (sufficient for detection purposes).
     *
     * @param factory function to create the parser (e.g. {@code MyParser::new})
     * @param lexer the lexer to read tokens from
     * @return the configured parser
     */
    protected <P extends Parser> P createParser(
            Function<TokenStream, P> factory,
            Lexer lexer) {
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        P parser = factory.apply(tokens);
        parser.removeErrorListeners();
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
        return parser;
    }

    /**
     * Get the 1-based line number from an ANTLR rule context.
     */
    protected int lineOf(ParserRuleContext ctx) {
        return ctx.getStart() != null ? ctx.getStart().getLine() : 0;
    }

    /**
     * Get the text content of an ANTLR rule context.
     */
    protected String textOf(ParserRuleContext ctx) {
        return ctx.getText();
    }

    /**
     * Get the original source text (with whitespace) for a rule context
     * by reading from the token stream.
     */
    protected String originalTextOf(ParserRuleContext ctx, CommonTokenStream tokens) {
        if (ctx.getStart() == null || ctx.getStop() == null) {
            return ctx.getText();
        }
        return tokens.getText(ctx.getStart(), ctx.getStop());
    }
}
