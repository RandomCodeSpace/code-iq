package io.github.randomcodespace.iq.intelligence.resolver.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import io.github.randomcodespace.iq.intelligence.resolver.EmptyResolved;
import io.github.randomcodespace.iq.intelligence.resolver.ResolutionException;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import io.github.randomcodespace.iq.intelligence.resolver.SymbolResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;

/**
 * Java backend for the resolver SPI. Wraps JavaParser's {@link JavaSymbolSolver}
 * configured from a {@link CombinedTypeSolver} that includes
 * {@link ReflectionTypeSolver} plus a {@link JavaParserTypeSolver} per source
 * root discovered by {@link JavaSourceRootDiscovery}.
 *
 * <p>Determinism: {@link JavaSourceRootDiscovery} returns roots sorted
 * alphabetically, so the order of {@link JavaParserTypeSolver}s in the
 * combined solver is stable across runs.
 *
 * <p>Thread safety: bootstrap is called once before any resolve(); resolve()
 * is safe under virtual-thread concurrency because {@link JavaSymbolSolver}
 * itself is thread-safe for read-only resolution. We deliberately do NOT
 * mutate {@code StaticJavaParser.getParserConfiguration()} — that would be
 * global static state shared with the existing
 * {@link io.github.randomcodespace.iq.detector.jvm.java.AbstractJavaParserDetector}
 * thread-local parser pool and is not safe under concurrent use.
 */
@Component
public class JavaSymbolResolver implements SymbolResolver {

    private final JavaSourceRootDiscovery discovery;
    // volatile: bootstrap() publishes the solver; resolve() and the public
    // accessors read it from arbitrary virtual-thread carriers. Without
    // volatile a reader could see a half-initialized JavaSymbolSolver — a
    // narrow race that the JLS Thread Start Rule covers for the
    // executor.submit() path but does NOT cover for callers that read the
    // public accessors after bootstrap on a different thread. The fence is
    // cheap; the alternative is a quiet correctness hole.
    private volatile CombinedTypeSolver combined;
    private volatile JavaSymbolSolver solver;

    public JavaSymbolResolver(JavaSourceRootDiscovery discovery) {
        this.discovery = discovery;
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public void bootstrap(Path projectRoot) throws ResolutionException {
        try {
            CombinedTypeSolver cts = new CombinedTypeSolver();
            cts.add(new ReflectionTypeSolver());
            for (Path root : discovery.discover(projectRoot)) {
                cts.add(new JavaParserTypeSolver(root.toFile()));
            }
            this.combined = cts;
            this.solver = new JavaSymbolSolver(cts);
        } catch (RuntimeException e) {
            throw new ResolutionException(
                    "JavaSymbolResolver bootstrap failed for " + projectRoot,
                    e, projectRoot, "java");
        }
    }

    @Override
    public Resolved resolve(DiscoveredFile file, Object parsedAst) {
        if (file == null || !"java".equalsIgnoreCase(file.language())) {
            return EmptyResolved.INSTANCE;
        }
        if (this.solver == null) {
            // bootstrap() not called or it failed silently — falling back to
            // EmptyResolved is the safe path. The orchestrator already logs
            // bootstrap failures from ResolverRegistry.
            return EmptyResolved.INSTANCE;
        }

        CompilationUnit cu;
        if (parsedAst instanceof CompilationUnit existing) {
            // Caller already parsed (Analyzer's structured-language path, or
            // a detector that pre-parsed). Reuse — no double-parse.
            cu = existing;
        } else if (parsedAst instanceof String source) {
            // Lazy parse: Analyzer passes the raw file content for Java
            // because the orchestrator-level structured parser doesn't cover
            // Java. A fresh JavaParser per call is intentional — JavaParser
            // instances aren't thread-safe and resolve() is invoked from
            // virtual threads concurrently. Allocation cost is small relative
            // to the parse itself, and the per-call instance carries the
            // symbol solver so resolve()s on the resulting AST work.
            ParserConfiguration cfg = new ParserConfiguration().setSymbolResolver(solver);
            ParseResult<CompilationUnit> parseResult = new JavaParser(cfg).parse(source);
            // Strict success check: JavaParser is permissive and may hand
            // back a partial CompilationUnit even when the source has parse
            // problems. Resolving against a partial CU silently emits
            // simple-name-only edges and looks like coverage even though
            // symbol resolution is broken. Treat any non-success as
            // "EmptyResolved, fall back to lexical" so the downstream graph
            // never carries phantom RESOLVED-tier edges from broken parses.
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return EmptyResolved.INSTANCE;
            }
            cu = parseResult.getResult().get();
        } else {
            // Neither a CompilationUnit nor a String — caller shape we don't
            // understand. Defensive fallback rather than a ClassCastException.
            return EmptyResolved.INSTANCE;
        }
        return new JavaResolved(cu, solver);
    }

    /**
     * @return the {@link CombinedTypeSolver} built during {@link #bootstrap(Path)},
     *         or null if bootstrap hasn't run. Exposed for tests + advanced use.
     */
    public CombinedTypeSolver combinedTypeSolver() {
        return combined;
    }

    /**
     * @return the {@link JavaSymbolSolver} built during {@link #bootstrap(Path)},
     *         or null if bootstrap hasn't run. Detectors that want to attach the
     *         solver to their own {@code JavaParser} (rather than the
     *         {@link JavaResolved#cu()} carried CompilationUnit) can read this
     *         and call {@code new ParserConfiguration().setSymbolResolver(...)}.
     */
    public JavaSymbolSolver symbolSolver() {
        return solver;
    }
}
