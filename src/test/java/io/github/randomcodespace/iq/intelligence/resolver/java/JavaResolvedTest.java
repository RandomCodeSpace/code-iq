package io.github.randomcodespace.iq.intelligence.resolver.java;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.github.randomcodespace.iq.intelligence.resolver.EmptyResolved;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import io.github.randomcodespace.iq.model.Confidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link JavaResolved}: the language-specific {@link Resolved}
 * subtype detectors downcast to. Verifies the three contract obligations —
 * isAvailable() == true, sourceConfidence() == RESOLVED, and the cu/solver
 * accessors expose what was passed in.
 */
class JavaResolvedTest {

    @Test
    void isAvailableIsTrue() {
        JavaResolved r = newResolved();
        assertTrue(r.isAvailable(),
                "JavaResolved must report available — it carries actual resolution");
    }

    @Test
    void sourceConfidenceIsResolved() {
        JavaResolved r = newResolved();
        assertEquals(Confidence.RESOLVED, r.sourceConfidence(),
                "JavaResolved is the RESOLVED tier — symbol-solver-backed");
    }

    @Test
    void cuAccessorReturnsTheParsedCompilationUnit() {
        CompilationUnit cu = StaticJavaParser.parse("class Foo {}");
        JavaSymbolSolver solver = new JavaSymbolSolver(new CombinedTypeSolver(new ReflectionTypeSolver()));
        JavaResolved r = new JavaResolved(cu, solver);
        assertSame(cu, r.cu());
    }

    @Test
    void solverAccessorReturnsTheConfiguredSolver() {
        CompilationUnit cu = StaticJavaParser.parse("class Foo {}");
        JavaSymbolSolver solver = new JavaSymbolSolver(new CombinedTypeSolver(new ReflectionTypeSolver()));
        JavaResolved r = new JavaResolved(cu, solver);
        assertSame(solver, r.solver());
    }

    @Test
    void implementsResolved() {
        // The interface contract — verified by isAssignableFrom rather than
        // an instanceof check (which the compiler already enforces).
        assertTrue(Resolved.class.isAssignableFrom(JavaResolved.class));
    }

    @Test
    void distinctFromEmptyResolvedSentinel() {
        // A real JavaResolved must be != EmptyResolved.INSTANCE so detectors
        // checking via `==` can short-circuit correctly.
        JavaResolved r = newResolved();
        assertNotSame(EmptyResolved.INSTANCE, r);
    }

    private static JavaResolved newResolved() {
        CompilationUnit cu = StaticJavaParser.parse("class Foo {}");
        JavaSymbolSolver solver = new JavaSymbolSolver(new CombinedTypeSolver(new ReflectionTypeSolver()));
        return new JavaResolved(cu, solver);
    }
}
