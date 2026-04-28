package io.github.randomcodespace.iq.detector;

import io.github.randomcodespace.iq.intelligence.resolver.EmptyResolved;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import io.github.randomcodespace.iq.model.Confidence;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggressive coverage for the {@link DetectorContext#resolved()} accessor and
 * the backward-compat invariant: existing call sites continue to compile and
 * see {@link Optional#empty()} for resolution. Detectors that opt in via
 * {@link DetectorContext#withResolved(Resolved)} get the attached value.
 */
class DetectorContextResolvedTest {

    @Test
    void threeArgConstructorDefaultsResolvedToEmpty() {
        DetectorContext ctx = new DetectorContext("Foo.java", "java", "class Foo {}");
        assertEquals(Optional.empty(), ctx.resolved(),
                "3-arg constructor still gives empty resolution — backward compat");
    }

    @Test
    void fiveArgConstructorDefaultsResolvedToEmpty() {
        DetectorContext ctx = new DetectorContext("Foo.java", "java", "class Foo {}",
                null, "myModule");
        assertEquals(Optional.empty(), ctx.resolved(),
                "5-arg constructor still gives empty resolution — backward compat");
    }

    @Test
    void sixArgConstructorDefaultsResolvedToEmpty() {
        DetectorContext ctx = new DetectorContext("Foo.java", "java", "class Foo {}",
                null, "myModule", null);
        assertEquals(Optional.empty(), ctx.resolved(),
                "6-arg constructor still gives empty resolution — backward compat");
    }

    @Test
    void canonicalSevenArgConstructorCarriesResolved() {
        Resolved r = stubAvailableResolved();
        DetectorContext ctx = new DetectorContext("Foo.java", "java", "class Foo {}",
                null, "myModule", null, Optional.of(r));
        assertTrue(ctx.resolved().isPresent());
        assertSame(r, ctx.resolved().get());
    }

    @Test
    void compactConstructorNormalizesNullResolvedToEmpty() {
        // Defensive: passing null Optional<Resolved> is a misuse, but the compact
        // constructor must not let it propagate (or callers reading ctx.resolved()
        // would NPE). Normalized to Optional.empty() at construction time.
        DetectorContext ctx = new DetectorContext("Foo.java", "java", "class Foo {}",
                null, "myModule", null, null);
        assertNotNull(ctx.resolved());
        assertEquals(Optional.empty(), ctx.resolved());
    }

    @Test
    void withResolvedAttachesAvailableResolved() {
        DetectorContext base = new DetectorContext("Foo.java", "java", "class Foo {}");
        Resolved r = stubAvailableResolved();
        DetectorContext withR = base.withResolved(r);

        // Original is untouched
        assertEquals(Optional.empty(), base.resolved());
        // Copy carries the resolution
        assertTrue(withR.resolved().isPresent());
        assertSame(r, withR.resolved().get());
    }

    @Test
    void withResolvedNullClearsResolution() {
        DetectorContext base = new DetectorContext("Foo.java", "java", "class Foo {}",
                null, "m", null, Optional.of(stubAvailableResolved()));
        DetectorContext cleared = base.withResolved(null);

        assertEquals(Optional.empty(), cleared.resolved(),
                "withResolved(null) clears the resolution back to empty");
    }

    @Test
    void withResolvedEmptyResolvedSentinelIsCarried() {
        // A detector that wants to explicitly say "the resolver tried but came
        // up empty" can attach EmptyResolved.INSTANCE — different semantics from
        // Optional.empty (which means "the resolver pass didn't run for this file").
        DetectorContext base = new DetectorContext("Foo.java", "java", "");
        DetectorContext withEmpty = base.withResolved(EmptyResolved.INSTANCE);

        assertTrue(withEmpty.resolved().isPresent(),
                "EmptyResolved.INSTANCE is a real value — Optional.isPresent() is true");
        assertSame(EmptyResolved.INSTANCE, withEmpty.resolved().get());
        assertFalse(withEmpty.resolved().get().isAvailable(),
                "but isAvailable() == false — detectors still fall back to syntactic");
    }

    @Test
    void withResolvedPreservesAllOtherFields() {
        // Verifying we don't accidentally drop other fields when copying.
        DetectorContext base = new DetectorContext("Foo.java", "java", "content",
                "parsedAst", "moduleName", null);
        DetectorContext copy = base.withResolved(EmptyResolved.INSTANCE);

        assertEquals("Foo.java", copy.filePath());
        assertEquals("java", copy.language());
        assertEquals("content", copy.content());
        assertEquals("parsedAst", copy.parsedData());
        assertEquals("moduleName", copy.moduleName());
        assertNull(copy.registry());
    }

    @Test
    void resolvedAccessorTypicalDetectorUsage() {
        // Documents the canonical detector-side check: filter on isAvailable
        // before downcasting to a language-specific Resolved subclass.
        DetectorContext ctxA = new DetectorContext("Foo.java", "java", "");
        DetectorContext ctxB = new DetectorContext("Foo.java", "java", "")
                .withResolved(EmptyResolved.INSTANCE);
        DetectorContext ctxC = new DetectorContext("Foo.java", "java", "")
                .withResolved(stubAvailableResolved());

        assertTrue(ctxA.resolved().filter(Resolved::isAvailable).isEmpty(),
                "no resolution attached: detector falls back to syntactic");
        assertTrue(ctxB.resolved().filter(Resolved::isAvailable).isEmpty(),
                "EmptyResolved attached: detector still falls back");
        assertTrue(ctxC.resolved().filter(Resolved::isAvailable).isPresent(),
                "available Resolved attached: detector may downcast and use it");
    }

    private static Resolved stubAvailableResolved() {
        return new Resolved() {
            @Override public boolean isAvailable() { return true; }
            @Override public Confidence sourceConfidence() { return Confidence.RESOLVED; }
        };
    }
}
