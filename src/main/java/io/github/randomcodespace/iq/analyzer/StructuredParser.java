package io.github.randomcodespace.iq.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Parses structured files (YAML, JSON, XML, TOML, INI, Properties)
 * into Maps/Objects for structured detectors.
 * <p>
 * Returns {@code null} on parse failure so callers can fall through
 * to regex-based detection.
 */
@Service
public class StructuredParser {

    private static final Logger log = LoggerFactory.getLogger(StructuredParser.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse structured file content into a Map or Object.
     *
     * @param language  the file language identifier
     * @param content   the raw file content as a string
     * @param filePath  the file path (for error messages)
     * @return parsed object, or {@code null} if the language is not structured or parsing fails
     */
    public Object parse(String language, String content, String filePath) {
        if (language == null || content == null) return null;

        try {
            return switch (language) {
                case "yaml" -> parseYaml(content);
                case "json" -> parseJson(content);
                case "xml" -> parseXml(content, filePath);
                case "toml" -> parseToml(content);
                case "ini" -> parseIni(content);
                case "properties" -> parseProperties(content);
                default -> null;
            };
        } catch (Exception e) {
            log.debug("Structured parse failed for {} ({}): {}", filePath, language, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Individual parsers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Object parseYaml(String content) {
        var yaml = new Yaml();
        var docs = new java.util.ArrayList<>();
        for (Object doc : yaml.loadAll(content)) {
            docs.add(doc);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (docs.size() <= 1) {
            result.put("type", "yaml");
            result.put("data", docs.isEmpty() ? null : docs.getFirst());
        } else {
            result.put("type", "yaml_multi");
            result.put("documents", docs);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object parseJson(String content) throws Exception {
        Object data = objectMapper.readValue(content, Object.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "json");
        result.put("data", data);
        return result;
    }

    private Object parseXml(String content, String filePath) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        // Allow DOCTYPE but prevent XXE attacks (avoids [Fatal Error] on stderr)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        // Suppress parse warnings/errors from printing to stderr
        builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
            @Override public void warning(org.xml.sax.SAXParseException e) {}
            @Override public void error(org.xml.sax.SAXParseException e) {}
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        });
        var doc = builder.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        var root = doc.getDocumentElement();
        // Return a simple map with root element info for structured detectors
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "xml");
        result.put("file", filePath);
        result.put("rootElement", root.getTagName());
        result.put("rootNamespace", root.getNamespaceURI());
        return result;
    }

    /**
     * Simple TOML parser — handles basic key=value, [section] headers,
     * and quoted string values. For full TOML compliance, consider a
     * dedicated library.
     */
    private Object parseToml(String content) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> currentSection = data;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            // Section header
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                String sectionName = trimmed.substring(1, trimmed.length() - 1).trim();
                currentSection = new LinkedHashMap<>();
                data.put(sectionName, currentSection);
                continue;
            }

            // Key = value
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                // Strip quotes
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                currentSection.put(key, value);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "toml");
        result.put("data", data);
        return result;
    }

    private Object parseIni(String content) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, String> currentSection = new LinkedHashMap<>();
        String sectionName = "DEFAULT";
        data.put(sectionName, currentSection);

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue;

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                sectionName = trimmed.substring(1, trimmed.length() - 1).trim();
                currentSection = new LinkedHashMap<>();
                data.put(sectionName, currentSection);
                continue;
            }

            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                currentSection.put(key, value);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "ini");
        result.put("data", data);
        return result;
    }

    private Object parseProperties(String content) throws Exception {
        var props = new Properties();
        props.load(new StringReader(content));
        Map<String, String> data = new LinkedHashMap<>();
        // Sort keys for determinism (Properties uses a HashTable internally)
        var sortedKeys = new java.util.TreeSet<>(props.stringPropertyNames());
        for (String key : sortedKeys) {
            data.put(key, props.getProperty(key));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "properties");
        result.put("data", data);
        return result;
    }
}
