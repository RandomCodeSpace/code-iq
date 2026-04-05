package io.github.randomcodespace.iq.grammar;

import io.github.randomcodespace.iq.grammar.cpp.CPP14Lexer;
import io.github.randomcodespace.iq.grammar.cpp.CPP14Parser;
import io.github.randomcodespace.iq.grammar.csharp.CSharpLexer;
import io.github.randomcodespace.iq.grammar.csharp.CSharpParser;
import io.github.randomcodespace.iq.grammar.golang.GoLexer;
import io.github.randomcodespace.iq.grammar.golang.GoParser;
import io.github.randomcodespace.iq.grammar.javascript.JavaScriptLexer;
import io.github.randomcodespace.iq.grammar.javascript.JavaScriptParser;
import io.github.randomcodespace.iq.grammar.kotlin.KotlinLexer;
import io.github.randomcodespace.iq.grammar.kotlin.KotlinParser;
import io.github.randomcodespace.iq.grammar.python.Python3Lexer;
import io.github.randomcodespace.iq.grammar.python.Python3Parser;
import io.github.randomcodespace.iq.grammar.rust.RustLexer;
import io.github.randomcodespace.iq.grammar.rust.RustParser;
import io.github.randomcodespace.iq.grammar.scala.ScalaLexer;
import io.github.randomcodespace.iq.grammar.scala.ScalaParser;
import io.github.randomcodespace.iq.grammar.typescript.TypeScriptLexer;
import io.github.randomcodespace.iq.grammar.typescript.TypeScriptParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Factory for creating ANTLR parsers for supported languages.
 * Provides a unified interface to parse source code into ANTLR parse trees.
 *
 * <p>Each language has a corresponding entry point rule (e.g., {@code file_input}
 * for Python, {@code compilationUnit} for C#). The factory handles lexer/parser
 * creation with error suppression and SLL prediction mode for speed.</p>
 *
 * <p>TypeScript has a dedicated grammar (from antlr/grammars-v4) that supports
 * decorators, type annotations, interfaces, generics, enums, and other TS-specific
 * syntax. JavaScript files continue to use the JavaScript grammar.</p>
 */
public final class AntlrParserFactory {

    private static final Logger log = LoggerFactory.getLogger(AntlrParserFactory.class);

    /**
     * Languages supported by the ANTLR parser infrastructure.
     */
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "python", "javascript", "typescript", "go", "csharp",
            "rust", "kotlin", "scala", "cpp"
    );

    /**
     * Thread-local cache to avoid re-parsing the same file for multiple detectors.
     * Key is the content String identity (same object reference = same file), value is the parse tree.
     * Each file is processed by a single thread, so thread-local is safe and avoids cross-thread contention.
     */
    private static final ThreadLocal<Map.Entry<String, ParseTree>> PARSE_CACHE = new ThreadLocal<>();

    private AntlrParserFactory() {
        // utility class
    }

    /**
     * Clear the parse cache for the current thread.
     * Call this after all detectors have run for a file.
     */
    public static void clearCache() {
        PARSE_CACHE.remove();
    }

    /**
     * Parse source code for the given language and return the parse tree.
     * Results are cached per-thread so multiple detectors on the same file
     * share a single parse.
     *
     * @param language the language identifier (e.g., "python", "go", "typescript")
     * @param content  the source code to parse
     * @return the ANTLR parse tree, or null if the language is not supported
     * @throws RuntimeException if parsing encounters a fatal error
     */
    public static ParseTree parse(String language, String content) {
        if (language == null || content == null || content.isBlank()) {
            return null;
        }

        // Bail out early if this thread was cancelled (e.g. via Future.cancel(true))
        // to avoid spending unbounded time in ANTLR's CPU-bound parse loop
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            return null;
        }

        // Check thread-local cache — same content object means same file
        var cached = PARSE_CACHE.get();
        if (cached != null && cached.getKey() == content) {
            return cached.getValue();
        }

        java.time.Instant parseStart = java.time.Instant.now();
        ParseTree tree = switch (language.toLowerCase()) {
            case "python" -> parsePython(content);
            case "javascript" -> parseJavaScript(content);
            case "typescript" -> parseTypeScript(content);
            case "go" -> parseGo(content);
            case "csharp" -> parseCSharp(content);
            case "rust" -> parseRust(content);
            case "kotlin" -> parseKotlin(content);
            case "scala" -> parseScala(content);
            case "cpp" -> parseCpp(content);
            default -> null;
        };
        long parseMs = java.time.Duration.between(parseStart, java.time.Instant.now()).toMillis();
        if (parseMs > 2000) {
            log.warn("[SLOW ANTLR] {} parse: {}ms ({} bytes)", language, parseMs, content.length());
        }

        // Cache the result for subsequent detectors on the same file
        if (tree != null) {
            PARSE_CACHE.set(Map.entry(content, tree));
        }
        return tree;
    }

    /**
     * Check if a language is supported by the ANTLR parser infrastructure.
     */
    public static boolean isSupported(String language) {
        return language != null && SUPPORTED_LANGUAGES.contains(language.toLowerCase());
    }

    // --- Language-specific parse methods ---

    private static ParseTree parsePython(String content) {
        Python3Lexer lexer = createLexer(Python3Lexer::new, content);
        Python3Parser parser = createParser(Python3Parser::new, lexer);
        return parser.file_input();
    }

    private static ParseTree parseJavaScript(String content) {
        JavaScriptLexer lexer = createLexer(JavaScriptLexer::new, content);
        JavaScriptParser parser = createParser(JavaScriptParser::new, lexer);
        return parser.program();
    }

    private static ParseTree parseTypeScript(String content) {
        TypeScriptLexer lexer = createLexer(TypeScriptLexer::new, content);
        TypeScriptParser parser = createParser(TypeScriptParser::new, lexer);
        return parser.program();
    }

    private static ParseTree parseGo(String content) {
        GoLexer lexer = createLexer(GoLexer::new, content);
        GoParser parser = createParser(GoParser::new, lexer);
        return parser.sourceFile();
    }

    private static ParseTree parseCSharp(String content) {
        CSharpLexer lexer = createLexer(CSharpLexer::new, content);
        CSharpParser parser = createParser(CSharpParser::new, lexer);
        return parser.compilation_unit();
    }

    private static ParseTree parseRust(String content) {
        RustLexer lexer = createLexer(RustLexer::new, content);
        RustParser parser = createParser(RustParser::new, lexer);
        return parser.crate();
    }

    private static ParseTree parseKotlin(String content) {
        KotlinLexer lexer = createLexer(KotlinLexer::new, content);
        KotlinParser parser = createParser(KotlinParser::new, lexer);
        return parser.kotlinFile();
    }

    private static ParseTree parseScala(String content) {
        ScalaLexer lexer = createLexer(ScalaLexer::new, content);
        ScalaParser parser = createParser(ScalaParser::new, lexer);
        return parser.compilationUnit();
    }

    private static ParseTree parseCpp(String content) {
        CPP14Lexer lexer = createLexer(CPP14Lexer::new, content);
        CPP14Parser parser = createParser(CPP14Parser::new, lexer);
        return parser.translationUnit();
    }

    // --- Shared helpers ---

    private static <L extends Lexer> L createLexer(Function<CharStream, L> factory, String content) {
        CharStream input = CharStreams.fromString(content);
        L lexer = factory.apply(input);
        lexer.removeErrorListeners();
        return lexer;
    }

    private static <P extends Parser> P createParser(Function<TokenStream, P> factory, Lexer lexer) {
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        P parser = factory.apply(tokens);
        parser.removeErrorListeners();
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
        return parser;
    }
}
