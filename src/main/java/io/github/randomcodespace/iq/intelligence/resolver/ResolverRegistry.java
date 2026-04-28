package io.github.randomcodespace.iq.intelligence.resolver;

import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring-managed registry for {@link SymbolResolver} backends. Mirrors
 * {@link io.github.randomcodespace.iq.detector.DetectorRegistry}: every
 * {@code @Component} implementing {@link SymbolResolver} is auto-injected via
 * the constructor.
 *
 * <p>Determinism: resolvers are sorted by {@link Class#getSimpleName()}
 * alphabetically before any other operation. {@link #bootstrap(Path)} iterates
 * in this order; per-language lookup uses "first-in-sort-order wins" if two
 * resolvers claim the same language. Same input → same resolution behavior,
 * every time.
 *
 * <p>Resilience: {@link #bootstrap(Path)} catches per-resolver
 * {@link ResolutionException} so one misbehaving resolver can't take down the
 * whole pass. Each resolver's own {@link SymbolResolver#resolve} handles its
 * post-bootstrap state — if bootstrap failed, the resolver should return
 * {@link EmptyResolved#INSTANCE} from its resolve() method (its own concern).
 */
@Service
public class ResolverRegistry {

    private static final Logger log = LoggerFactory.getLogger(ResolverRegistry.class);

    /** Singleton no-op resolver — returned for unknown languages or null input. */
    static final SymbolResolver NOOP = new NoopResolver();

    private final List<SymbolResolver> resolvers;
    private final Map<String, SymbolResolver> byLanguage;

    public ResolverRegistry(List<SymbolResolver> resolvers) {
        // Deterministic order: alphabetical by class simple name.
        this.resolvers = resolvers.stream()
                .sorted(Comparator.comparing(r -> r.getClass().getSimpleName()))
                .toList();

        // First-in-sort-order wins per language (deterministic conflict resolution).
        Map<String, SymbolResolver> map = new HashMap<>();
        for (SymbolResolver r : this.resolvers) {
            for (String lang : r.getSupportedLanguages()) {
                if (lang == null || lang.isBlank()) continue;
                map.putIfAbsent(lang.toLowerCase(), r);
            }
        }
        this.byLanguage = Map.copyOf(map);
    }

    /**
     * Bootstrap every registered resolver against the given project root.
     * Iterates in deterministic (alphabetical) order. Per-resolver failures
     * are logged at WARN and swallowed so one broken resolver doesn't cascade.
     */
    public void bootstrap(Path projectRoot) {
        for (SymbolResolver r : resolvers) {
            try {
                r.bootstrap(projectRoot);
            } catch (ResolutionException e) {
                log.warn("resolver {} bootstrap failed for {}: {}",
                        r.getClass().getSimpleName(), projectRoot, e.getMessage());
            } catch (RuntimeException e) {
                // Defensive — resolvers shouldn't throw RuntimeException, but
                // if they do, don't take down the pass.
                log.warn("resolver {} bootstrap threw unexpectedly for {}: {}",
                        r.getClass().getSimpleName(), projectRoot, e.toString());
            }
        }
    }

    /**
     * Look up the resolver for a given language identifier.
     *
     * @param language language identifier (case-insensitive). May be null.
     * @return the matching resolver, or a no-op resolver returning
     *         {@link EmptyResolved#INSTANCE}. Never null.
     */
    public SymbolResolver resolverFor(String language) {
        if (language == null) return NOOP;
        return byLanguage.getOrDefault(language.toLowerCase(), NOOP);
    }

    /** @return all registered resolvers in deterministic order (alphabetical by class simple name). */
    public List<SymbolResolver> all() {
        return resolvers;
    }

    /** Singleton no-op — claims no languages, bootstrap is a no-op, resolve always returns EmptyResolved. */
    static final class NoopResolver implements SymbolResolver {
        @Override public Set<String> getSupportedLanguages() { return Set.of(); }
        @Override public void bootstrap(Path projectRoot) { }
        @Override public Resolved resolve(DiscoveredFile file, Object parsedAst) {
            return EmptyResolved.INSTANCE;
        }
    }
}
