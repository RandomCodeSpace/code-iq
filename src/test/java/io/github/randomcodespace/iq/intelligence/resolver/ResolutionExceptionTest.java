package io.github.randomcodespace.iq.intelligence.resolver;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggressive coverage for {@link ResolutionException}. Verifies it carries
 * actionable context (file + language) so the orchestrator can log usefully.
 */
class ResolutionExceptionTest {

    @Test
    void carriesMessageFileAndLanguage() {
        Path p = Path.of("/tmp/Foo.java");
        ResolutionException e = new ResolutionException("bootstrap failed", p, "java");

        assertEquals("bootstrap failed", e.getMessage());
        assertEquals(p, e.file());
        assertEquals("java", e.language());
        assertNull(e.getCause(), "no underlying cause when constructed without one");
    }

    @Test
    void carriesUnderlyingCause() {
        Path p = Path.of("/tmp/Foo.java");
        Exception root = new IllegalStateException("classpath broken");
        ResolutionException e = new ResolutionException("bootstrap failed", root, p, "java");

        assertSame(root, e.getCause(), "underlying cause is preserved");
        assertEquals(p, e.file());
        assertEquals("java", e.language());
    }

    @Test
    void nullFileAndLanguageAreAllowed() {
        // Defensive: some callers may not have file/language at hand.
        // The exception should still construct without NPE.
        ResolutionException e = new ResolutionException("generic failure", null, null);
        assertNull(e.file());
        assertNull(e.language());
        assertEquals("generic failure", e.getMessage());
    }

    @Test
    void isCheckedException() {
        // The exception is checked by design — orchestrators must catch and
        // decide whether to skip the file or abort the pass.
        assertFalse(RuntimeException.class.isAssignableFrom(ResolutionException.class),
                "ResolutionException must be a checked exception (subclass of Exception, not RuntimeException)");
        assertTrue(Exception.class.isAssignableFrom(ResolutionException.class));
    }
}
