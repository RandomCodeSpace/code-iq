package io.github.randomcodespace.iq.intelligence.resolver.java;

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
    private CombinedTypeSolver combined;
    private JavaSymbolSolver solver;

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
        if (!(parsedAst instanceof CompilationUnit cu)) {
            return EmptyResolved.INSTANCE;
        }
        if (this.solver == null) {
            // bootstrap() not called or it failed silently — falling back to
            // EmptyResolved is the safe path. The orchestrator already logs
            // bootstrap failures from ResolverRegistry.
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
