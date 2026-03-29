package io.github.randomcodespace.iq.health;

import io.github.randomcodespace.iq.graph.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator that reports whether the graph database
 * has been populated with nodes.
 */
@Component
@ConditionalOnBean(GraphStore.class)
public class GraphHealthIndicator implements HealthIndicator {

    private final GraphStore graphStore;

    public GraphHealthIndicator(GraphStore graphStore) {
        this.graphStore = graphStore;
    }

    @Override
    public Health health() {
        try {
            long count = graphStore.count();
            if (count > 0) {
                return Health.up()
                        .withDetail("nodes", count)
                        .build();
            } else {
                return Health.down()
                        .withDetail("reason", "No graph data")
                        .withDetail("nodes", 0)
                        .build();
            }
        } catch (Exception e) {
            return Health.down()
                    .withDetail("reason", "Graph store unavailable")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
