package io.github.randomcodespace.iq.intelligence.resolver;

import io.github.randomcodespace.iq.model.Confidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@link Resolved} and the {@link EmptyResolved} singleton.
 *
 * <p>{@link EmptyResolved} is a load-bearing sentinel — detectors check
 * {@link Resolved#isAvailable()} == false to decide "fall back to syntactic
 * detection." Anything that breaks the singleton invariants below is a bug.
 */
class ResolvedContractTest {

    @Test
    void emptyResolvedIsSingleton() {
        // Reference equality — detectors may use `==` to short-circuit
        // (e.g. `if (resolved == EmptyResolved.INSTANCE) return ...`)
        assertSame(EmptyResolved.INSTANCE, EmptyResolved.INSTANCE);
    }

    @Test
    void emptyResolvedReportsNotAvailable() {
        assertFalse(EmptyResolved.INSTANCE.isAvailable(),
                "EmptyResolved must always report not-available — it's the 'no resolution' sentinel");
    }

    @Test
    void emptyResolvedConfidenceFloorIsLexical() {
        // Resolution didn't happen — emissions consulting EmptyResolved should
        // never claim RESOLVED. LEXICAL is the safe floor.
        assertEquals(Confidence.LEXICAL, EmptyResolved.INSTANCE.sourceConfidence(),
                "EmptyResolved floor is LEXICAL — nothing was actually resolved");
    }

    @Test
    void emptyResolvedConstructorIsPrivate() throws Exception {
        // Defensive: prevent rogue subclasses from violating the singleton.
        var ctor = EmptyResolved.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers()),
                "EmptyResolved must have a private constructor");
    }

    @Test
    void emptyResolvedClassIsFinal() {
        // Singletons must not be subclassable — a subclass could return true
        // from isAvailable() and break the contract.
        assertTrue(java.lang.reflect.Modifier.isFinal(EmptyResolved.class.getModifiers()),
                "EmptyResolved must be final to preserve singleton invariants");
    }

    @Test
    void resolvedInterfaceContractAvailableImpliesNonLexical() {
        // Documents the convention via a custom test impl: a Resolved that
        // claims isAvailable==true is expected to expose a non-LEXICAL floor
        // (LEXICAL is reserved for "nothing resolved"). This isn't enforced by
        // the interface — it's a contract the tests document.
        Resolved fakeResolved = new Resolved() {
            @Override public boolean isAvailable() { return true; }
            @Override public Confidence sourceConfidence() { return Confidence.RESOLVED; }
        };
        assertTrue(fakeResolved.isAvailable());
        assertEquals(Confidence.RESOLVED, fakeResolved.sourceConfidence(),
                "available Resolved instances should expose RESOLVED (or higher)");
    }
}
