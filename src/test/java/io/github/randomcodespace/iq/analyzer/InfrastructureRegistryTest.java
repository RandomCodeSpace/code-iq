package io.github.randomcodespace.iq.analyzer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InfrastructureRegistryTest {

    private InfrastructureRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InfrastructureRegistry();
    }

    @Test
    void startsEmpty() {
        assertTrue(registry.isEmpty());
        assertEquals(0, registry.size());
        assertNull(registry.getServiceName());
    }

    @Test
    void registerAndRetrieveByKind() {
        registry.register(endpoint("db:test", InfraEndpoint.Kind.DATABASE, "mydb", "postgresql"));
        registry.register(endpoint("topic:test", InfraEndpoint.Kind.TOPIC, "events", "kafka"));
        registry.register(endpoint("queue:test", InfraEndpoint.Kind.QUEUE, "tasks", "rabbitmq"));
        registry.register(endpoint("cache:test", InfraEndpoint.Kind.CACHE, "sessions", "redis"));
        registry.register(endpoint("api:test", InfraEndpoint.Kind.EXTERNAL_API, "stripe", "http"));

        assertEquals(1, registry.getDatabases().size());
        assertEquals(1, registry.getTopics().size());
        assertEquals(1, registry.getQueues().size());
        assertEquals(1, registry.getCaches().size());
        assertEquals(1, registry.getExternalApis().size());
        assertEquals(5, registry.size());
    }

    @Test
    void getAllReturnsSortedById() {
        registry.register(endpoint("z:last",  InfraEndpoint.Kind.DATABASE, "z", "postgresql"));
        registry.register(endpoint("a:first", InfraEndpoint.Kind.CACHE,    "a", "redis"));
        registry.register(endpoint("m:mid",   InfraEndpoint.Kind.TOPIC,    "m", "kafka"));

        List<InfraEndpoint> all = registry.getAll();
        assertEquals(3, all.size());
        assertEquals("a:first", all.get(0).id());
        assertEquals("m:mid",   all.get(1).id());
        assertEquals("z:last",  all.get(2).id());
    }

    @Test
    void getAllIsUnmodifiable() {
        registry.register(endpoint("db:x", InfraEndpoint.Kind.DATABASE, "x", "postgresql"));
        List<InfraEndpoint> all = registry.getAll();
        assertThrows(UnsupportedOperationException.class, () -> all.add(null));
    }

    @Test
    void typedMapsAreUnmodifiable() {
        registry.register(endpoint("db:x", InfraEndpoint.Kind.DATABASE, "x", "h2"));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.getDatabases().put("rogue", null));
    }

    @Test
    void registerOverwritesDuplicateId() {
        registry.register(endpoint("db:1", InfraEndpoint.Kind.DATABASE, "old", "h2"));
        registry.register(endpoint("db:1", InfraEndpoint.Kind.DATABASE, "new", "postgresql"));

        assertEquals(1, registry.getDatabases().size());
        assertEquals("postgresql", registry.getDatabases().get("db:1").type());
    }

    @Test
    void setAndGetServiceName() {
        registry.setServiceName("order-service");
        assertEquals("order-service", registry.getServiceName());
    }

    @Test
    void clearResetsEverything() {
        registry.register(endpoint("db:x", InfraEndpoint.Kind.DATABASE, "x", "postgresql"));
        registry.setServiceName("svc");

        registry.clear();

        assertTrue(registry.isEmpty());
        assertNull(registry.getServiceName());
    }

    @Test
    void endpointPropertiesAreImmutable() {
        var ep = new InfraEndpoint("id", InfraEndpoint.Kind.DATABASE, "db", "postgresql",
                null, Map.of("key", "value"));
        assertThrows(UnsupportedOperationException.class, () -> ep.properties().put("rogue", "x"));
    }

    @Test
    void endpointPropertiesAreSorted() {
        var ep = new InfraEndpoint("id", InfraEndpoint.Kind.DATABASE, "db", "postgresql",
                null, Map.of("z_key", "z", "a_key", "a", "m_key", "m"));
        List<String> keys = List.copyOf(ep.properties().keySet());
        assertEquals(List.of("a_key", "m_key", "z_key"), keys);
    }

    @Test
    void determinismSameOrderAcrossRegistrations() {
        // Register in reverse order, verify getAll is still sorted
        for (int i = 10; i >= 1; i--) {
            registry.register(endpoint("db:" + String.format("%02d", i),
                    InfraEndpoint.Kind.DATABASE, "db" + i, "postgresql"));
        }
        List<InfraEndpoint> all = registry.getAll();
        for (int i = 0; i < all.size() - 1; i++) {
            assertTrue(all.get(i).id().compareTo(all.get(i + 1).id()) < 0,
                    "Expected sorted order at index " + i);
        }
    }

    // -------------------------------------------------------------------------

    private static InfraEndpoint endpoint(String id, InfraEndpoint.Kind kind, String name, String type) {
        return new InfraEndpoint(id, kind, name, type, null, Map.of());
    }
}
