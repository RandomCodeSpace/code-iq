package io.github.randomcodespace.iq.analyzer;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A discovered infrastructure endpoint (database, message broker, cache, or external API).
 *
 * @param id            unique identifier (e.g. "db:spring.datasource", "topic:compose:kafka")
 * @param kind          infrastructure category
 * @param name          logical name (service name or config key)
 * @param type          specific technology (postgresql, kafka, redis, rabbitmq, etc.)
 * @param connectionUrl connection string if found, or null
 * @param properties    additional metadata (source, detection method, ports, etc.)
 */
public record InfraEndpoint(
        String id,
        Kind kind,
        String name,
        String type,
        String connectionUrl,
        Map<String, String> properties
) {

    /** Infrastructure categories. */
    public enum Kind {
        DATABASE,
        TOPIC,
        QUEUE,
        CACHE,
        EXTERNAL_API
    }

    /** Canonical constructor — ensures properties are always immutable and deterministically sorted. */
    public InfraEndpoint {
        properties = Collections.unmodifiableMap(
                new TreeMap<>(properties != null ? properties : Map.of()));
    }
}
