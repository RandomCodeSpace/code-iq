package io.github.randomcodespace.iq.detector.jvm.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.model.Confidence;

import java.util.Optional;

/**
 * Abstract base class for Java detectors that use JavaParser AST parsing
 * with regex fallback for malformed source files.
 */
public abstract class AbstractJavaParserDetector extends AbstractRegexDetector {

    private static final ThreadLocal<JavaParser> PARSER =
            ThreadLocal.withInitial(JavaParser::new);

    /**
     * JavaParser produces an AST — bump the inherited regex-default
     * {@link Confidence#LEXICAL} up to {@link Confidence#SYNTACTIC}. Detectors
     * that resolve symbols via JavaSymbolSolver (Phase 6+) should call
     * {@code setConfidence(RESOLVED)} on emissions.
     */
    @Override
    public Confidence defaultConfidence() {
        return Confidence.SYNTACTIC;
    }

    /**
     * Attempt to parse the source content into a JavaParser CompilationUnit.
     */
    protected Optional<CompilationUnit> parse(DetectorContext ctx) {
        try {
            if (ctx.content() == null || ctx.content().isEmpty()) {
                return Optional.empty();
            }
            return PARSER.get().parse(ctx.content()).getResult();
        } catch (Exception | AssertionError e) {
            // JavaParser may throw AssertionError for unrecognized token kinds
            // (e.g. newer Java syntax). Fall back to regex in those cases.
            return Optional.empty();
        } finally {
            PARSER.remove();
        }
    }

    /**
     * Extract the package name from a CompilationUnit.
     */
    protected String resolvePackage(CompilationUnit cu) {
        return cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
    }

    /**
     * Resolve a fully qualified name for a class within a CompilationUnit.
     */
    protected String resolveFqn(CompilationUnit cu, String className) {
        String pkg = resolvePackage(cu);
        return pkg.isEmpty() ? className : pkg + "." + className;
    }
}
