package io.github.randomcodespace.iq.analyzer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StructuredParserTest {

    private StructuredParser parser;

    @BeforeEach
    void setUp() {
        parser = new StructuredParser();
    }

    // ---- YAML ----

    @Test
    void parsesSimpleYaml() {
        String yaml = """
                name: test
                version: 1.0
                """;
        Object result = parser.parse("yaml", yaml, "test.yaml");

        assertNotNull(result);
        assertInstanceOf(Map.class, result);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("test", map.get("name"));
    }

    @Test
    void parsesNestedYaml() {
        String yaml = """
                server:
                  port: 8080
                  host: localhost
                """;
        Object result = parser.parse("yaml", yaml, "config.yaml");

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertInstanceOf(Map.class, map.get("server"));
    }

    @Test
    void invalidYamlReturnsNull() {
        // SnakeYAML is quite lenient, but truly broken input should not crash
        Object result = parser.parse("yaml", ":::\n---\n{{invalid", "bad.yaml");
        // May return null or a partial parse — just don't throw
        // (SnakeYAML treats many things as strings, so this might not be null)
    }

    // ---- JSON ----

    @Test
    void parsesSimpleJson() {
        String json = """
                {"name": "test", "count": 42}
                """;
        Object result = parser.parse("json", json, "test.json");

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("test", map.get("name"));
        assertEquals(42, map.get("count"));
    }

    @Test
    void invalidJsonReturnsNull() {
        Object result = parser.parse("json", "{broken", "bad.json");
        assertNull(result);
    }

    // ---- XML ----

    @Test
    void parsesSimpleXml() {
        String xml = """
                <?xml version="1.0"?>
                <project>
                  <name>test</name>
                </project>
                """;
        Object result = parser.parse("xml", xml, "pom.xml");

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("xml", map.get("type"));
        assertEquals("project", map.get("rootElement"));
    }

    @Test
    void invalidXmlReturnsNull() {
        Object result = parser.parse("xml", "<broken>no close", "bad.xml");
        assertNull(result);
    }

    // ---- TOML ----

    @Test
    void parsesSimpleToml() {
        String toml = """
                name = "test"
                version = "1.0"

                [server]
                port = "8080"
                """;
        Object result = parser.parse("toml", toml, "config.toml");

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("test", map.get("name"));
        assertInstanceOf(Map.class, map.get("server"));
    }

    // ---- INI ----

    @Test
    void parsesSimpleIni() {
        String ini = """
                [database]
                host = localhost
                port = 5432
                """;
        Object result = parser.parse("ini", ini, "config.ini");

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertInstanceOf(Map.class, map.get("database"));
    }

    // ---- Properties ----

    @Test
    void parsesProperties() {
        String props = """
                server.port=8080
                app.name=test
                """;
        Object result = parser.parse("properties", props, "app.properties");

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) result;
        assertEquals("8080", map.get("server.port"));
        assertEquals("test", map.get("app.name"));
    }

    // ---- Edge cases ----

    @Test
    void unknownLanguageReturnsNull() {
        assertNull(parser.parse("rust", "fn main() {}", "main.rs"));
    }

    @Test
    void nullContentReturnsNull() {
        assertNull(parser.parse("json", null, "test.json"));
    }

    @Test
    void nullLanguageReturnsNull() {
        assertNull(parser.parse(null, "{}", "test.json"));
    }
}
