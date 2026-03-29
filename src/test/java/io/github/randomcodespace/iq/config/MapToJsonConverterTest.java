package io.github.randomcodespace.iq.config;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Values;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MapToJsonConverterTest {

    private final MapToJsonConverter converter = new MapToJsonConverter();

    @Test
    void writeNullReturnsEmptyJson() {
        var result = converter.write(null);
        assertEquals("{}", result.asString());
    }

    @Test
    void writeEmptyMapReturnsEmptyJson() {
        var result = converter.write(Map.of());
        assertEquals("{}", result.asString());
    }

    @Test
    void writePopulatedMapReturnsJson() {
        Map<String, Object> map = new HashMap<>();
        map.put("key", "value");
        map.put("count", 42);
        var result = converter.write(map);
        String json = result.asString();
        assertTrue(json.contains("\"key\""));
        assertTrue(json.contains("\"value\""));
        assertTrue(json.contains("42"));
    }

    @Test
    void readNullReturnsEmptyMap() {
        var result = converter.read(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void readNullValueReturnsEmptyMap() {
        var result = converter.read(Values.NULL);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void readValidJsonReturnsMap() {
        var result = converter.read(Values.value("{\"name\":\"test\",\"count\":5}"));
        assertEquals("test", result.get("name"));
        assertEquals(5, result.get("count"));
    }

    @Test
    void readInvalidJsonReturnsEmptyMap() {
        var result = converter.read(Values.value("not-json"));
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void roundTrip() {
        Map<String, Object> original = new HashMap<>();
        original.put("framework", "spring");
        original.put("version", 3);

        var written = converter.write(original);
        var readBack = converter.read(written);

        assertEquals("spring", readBack.get("framework"));
        assertEquals(3, readBack.get("version"));
    }
}
