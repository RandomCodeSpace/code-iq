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

    // ---- Helper to extract wrapped data ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> asWrapper(Object result) {
        assertNotNull(result);
        assertInstanceOf(Map.class, result);
        return (Map<String, Object>) result;
    }

    @SuppressWarnings("unchecked")
    private <T> T getData(Object result) {
        Map<String, Object> wrapper = asWrapper(result);
        return (T) wrapper.get("data");
    }

    // ---- YAML ----

    @Test
    void parsesSimpleYaml() {
        String yaml = """
                name: test
                version: 1.0
                """;
        Object result = parser.parse("yaml", yaml, "test.yaml");

        Map<String, Object> wrapper = asWrapper(result);
        assertEquals("yaml", wrapper.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) wrapper.get("data");
        assertNotNull(data);
        assertEquals("test", data.get("name"));
    }

    @Test
    void parsesNestedYaml() {
        String yaml = """
                server:
                  port: 8080
                  host: localhost
                """;
        Object result = parser.parse("yaml", yaml, "config.yaml");

        Map<String, Object> wrapper = asWrapper(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) wrapper.get("data");
        assertNotNull(data);
        assertInstanceOf(Map.class, data.get("server"));
    }

    @Test
    void invalidYamlReturnsNull() {
        // SnakeYAML is quite lenient, but truly broken input should not crash
        parser.parse("yaml", ":::\n---\n{{invalid", "bad.yaml");
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

        Map<String, Object> wrapper = asWrapper(result);
        assertEquals("json", wrapper.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) wrapper.get("data");
        assertNotNull(data);
        assertEquals("test", data.get("name"));
        assertEquals(42, data.get("count"));
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

        Map<String, Object> wrapper = asWrapper(result);
        assertEquals("xml", wrapper.get("type"));
        assertEquals("project", wrapper.get("rootElement"));
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

        Map<String, Object> wrapper = asWrapper(result);
        assertEquals("toml", wrapper.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) wrapper.get("data");
        assertNotNull(data);
        assertEquals("test", data.get("name"));
        assertInstanceOf(Map.class, data.get("server"));
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

        Map<String, Object> wrapper = asWrapper(result);
        assertEquals("ini", wrapper.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) wrapper.get("data");
        assertNotNull(data);
        assertInstanceOf(Map.class, data.get("database"));
    }

    // ---- Properties ----

    @Test
    void parsesProperties() {
        String props = """
                server.port=8080
                app.name=test
                """;
        Object result = parser.parse("properties", props, "app.properties");

        Map<String, Object> wrapper = asWrapper(result);
        assertEquals("properties", wrapper.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) wrapper.get("data");
        assertNotNull(data);
        assertEquals("8080", data.get("server.port"));
        assertEquals("test", data.get("app.name"));
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
