package io.github.randomcodespace.iq.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Holds all infrastructure endpoints discovered during Phase 1 config scanning.
 * <p>
 * Built once by {@link ConfigScanner} before the main analysis pipeline runs,
 * then read-only during detector execution (Phase 2).
 * <p>
 * All internal maps are {@link TreeMap}-backed to guarantee deterministic iteration order.
 */
public class InfrastructureRegistry {

    private final TreeMap<String, InfraEndpoint> databases    = new TreeMap<>();
    private final TreeMap<String, InfraEndpoint> topics       = new TreeMap<>();
    private final TreeMap<String, InfraEndpoint> queues       = new TreeMap<>();
    private final TreeMap<String, InfraEndpoint> caches       = new TreeMap<>();
    private final TreeMap<String, InfraEndpoint> externalApis = new TreeMap<>();

    /** Service name extracted from spring.application.name or equivalent. */
    private String serviceName;

    // -------------------------------------------------------------------------
    // Mutation (Phase 1 only)
    // -------------------------------------------------------------------------

    /**
     * Register an endpoint into the appropriate typed map.
     * If an endpoint with the same id already exists it is silently overwritten,
     * allowing more-specific config sources to override less-specific ones.
     */
    public void register(InfraEndpoint endpoint) {
        switch (endpoint.kind()) {
            case DATABASE    -> databases.put(endpoint.id(), endpoint);
            case TOPIC       -> topics.put(endpoint.id(), endpoint);
            case QUEUE       -> queues.put(endpoint.id(), endpoint);
            case CACHE       -> caches.put(endpoint.id(), endpoint);
            case EXTERNAL_API -> externalApis.put(endpoint.id(), endpoint);
        }
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public void clear() {
        databases.clear();
        topics.clear();
        queues.clear();
        caches.clear();
        externalApis.clear();
        serviceName = null;
    }

    // -------------------------------------------------------------------------
    // Read-only accessors (Phase 2)
    // -------------------------------------------------------------------------

    public Map<String, InfraEndpoint> getDatabases()    { return Collections.unmodifiableMap(databases); }
    public Map<String, InfraEndpoint> getTopics()       { return Collections.unmodifiableMap(topics); }
    public Map<String, InfraEndpoint> getQueues()       { return Collections.unmodifiableMap(queues); }
    public Map<String, InfraEndpoint> getCaches()       { return Collections.unmodifiableMap(caches); }
    public Map<String, InfraEndpoint> getExternalApis() { return Collections.unmodifiableMap(externalApis); }

    /** Returns all endpoints sorted by id — deterministic across runs. */
    public List<InfraEndpoint> getAll() {
        List<InfraEndpoint> all = new ArrayList<>(size());
        all.addAll(databases.values());
        all.addAll(topics.values());
        all.addAll(queues.values());
        all.addAll(caches.values());
        all.addAll(externalApis.values());
        all.sort(java.util.Comparator.comparing(InfraEndpoint::id));
        return Collections.unmodifiableList(all);
    }

    public String getServiceName() { return serviceName; }

    public int size() {
        return databases.size() + topics.size() + queues.size()
                + caches.size() + externalApis.size();
    }

    public boolean isEmpty() { return size() == 0; }
}
