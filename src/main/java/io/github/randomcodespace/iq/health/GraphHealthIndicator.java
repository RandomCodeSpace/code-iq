package io.github.randomcodespace.iq.health;

import io.github.randomcodespace.iq.graph.GraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports whether the embedded Neo4j graph is populated and readable.
 *
 * <p><b>30-second TTL cache.</b> Every readiness probe (Kubernetes runs at
 * ~1 Hz by default) and every Spring Boot Actuator aggregator hit calls
 * {@link #health()}. Without caching, each probe runs a Cypher
 * {@code MATCH (n) RETURN count(n)} — the count is cheap on the index but
 * still wakes the page cache and competes with API traffic. We cache the
 * full {@link Health} object for 30s; that's well below the 60s
 * {@code initialDelaySeconds} a typical k8s readiness probe waits before
 * the first failure can evict a pod, so a stale "DOWN" reading still
 * marks the pod unready promptly.
 *
 * <p><b>Atomic reference, no lock.</b> {@link AtomicReference#compareAndSet}
 * means concurrent first-readers may each compute once, but the result is
 * shared via {@code AtomicReference.set} on the winner. Lock-free path is
 * critical because virtual-thread serving carriers can issue thousands of
 * concurrent health checks during burst.
 *
 * <p><b>Error message scrubbing.</b> The error path used to surface
 * {@code e.getMessage()} into {@code Health.down().withDetail("error", ...)}.
 * Liveness/readiness probes are permitAll on this surface, so anything in
 * the {@code error} detail is publicly readable. Stripped to just a class
 * name + a static reason — operators correlate via the WARN log line that
 * carries the full exception.
 */
// Profile-gated rather than @ConditionalOnBean(GraphStore.class) — the
// latter is documented as fragile on user @Component classes (its
// evaluation depends on bean-definition ordering during scan, and the
// readiness group config in application.yml references this bean by
// name). @Profile("serving") activates earlier, in lockstep with
// GraphStore (also a serving-profile bean), so Spring's
// "Included health contributor 'graphHealthIndicator' in group
// 'readiness' does not exist" startup validation passes deterministically.
@Component
@Profile("serving")
public class GraphHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(GraphHealthIndicator.class);
    static final Duration TTL = Duration.ofSeconds(30);

    private final GraphStore graphStore;
    private final AtomicReference<CachedHealth> cache = new AtomicReference<>();

    public GraphHealthIndicator(GraphStore graphStore) {
        this.graphStore = graphStore;
    }

    @Override
    public Health health() {
        CachedHealth current = cache.get();
        long now = System.nanoTime();
        if (current != null && (now - current.computedAtNanos) < TTL.toNanos()) {
            return current.health;
        }
        Health fresh = compute();
        // Best-effort cache; if a concurrent caller already set a value,
        // we still return our own fresh result. No retries — the next
        // caller will see the latest.
        cache.set(new CachedHealth(fresh, now));
        return fresh;
    }

    private Health compute() {
        try {
            long count = graphStore.count();
            if (count > 0) {
                return Health.up()
                        .withDetail("nodes", count)
                        .build();
            }
            return Health.down()
                    .withDetail("reason", "no_graph_data")
                    .withDetail("nodes", 0)
                    .build();
        } catch (Exception e) {
            // CodeQL java/error-message-exposure: never put the exception
            // message in the response. Health endpoints are permitAll —
            // any detail leak goes to anonymous probers. Operators
            // correlate via the WARN log line below.
            log.warn("GraphStore probe failed", e);
            return Health.down()
                    .withDetail("reason", "graph_store_unavailable")
                    .withDetail("error_class", e.getClass().getSimpleName())
                    .build();
        }
    }

    private record CachedHealth(Health health, long computedAtNanos) {}
}
