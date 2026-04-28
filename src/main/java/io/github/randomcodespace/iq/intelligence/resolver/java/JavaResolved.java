package io.github.randomcodespace.iq.intelligence.resolver.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import io.github.randomcodespace.iq.model.Confidence;

/**
 * Java-specific {@link Resolved} carrying the parsed {@link CompilationUnit}
 * and the {@link JavaSymbolSolver} configured for the current project.
 *
 * <p>Detectors that opt in to resolution should:
 * <ol>
 *   <li>Read {@code ctx.resolved()}</li>
 *   <li>Filter on {@link #isAvailable()}</li>
 *   <li>Downcast to {@code JavaResolved}</li>
 *   <li>Use {@link #cu()} (the file's parsed AST) and {@link #solver()}
 *       (for cross-file type lookups) to resolve symbols</li>
 * </ol>
 */
public record JavaResolved(CompilationUnit cu, JavaSymbolSolver solver) implements Resolved {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Confidence sourceConfidence() {
        return Confidence.RESOLVED;
    }
}
