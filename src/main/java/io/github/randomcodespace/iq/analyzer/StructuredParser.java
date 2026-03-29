package io.github.randomcodespace.iq.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

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
        // loadAll handles multi-doc YAML; return first document as a Map
        var docs = yaml.loadAll(content);
        for (Object doc : docs) {
            if (doc instanceof Map<?, ?>) {
                return doc;
            }
        }
        // Single non-map document — return as-is
        return yaml.load(content);
    }

    @SuppressWarnings("unchecked")
    private Object parseJson(String content) throws Exception {
        return objectMapper.readValue(content, Object.class);
    }

    private Object parseXml(String content, String filePath) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        // Disable external entities for security
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var builder = factory.newDocumentBuilder();
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
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> currentSection = result;
        String currentSectionName = null;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            // Section header
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSectionName = trimmed.substring(1, trimmed.length() - 1).trim();
                currentSection = new LinkedHashMap<>();
                result.put(currentSectionName, currentSection);
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
        return result;
    }

    private Object parseIni(String content) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> currentSection = new LinkedHashMap<>();
        String sectionName = "DEFAULT";
        result.put(sectionName, currentSection);

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue;

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                sectionName = trimmed.substring(1, trimmed.length() - 1).trim();
                currentSection = new LinkedHashMap<>();
                result.put(sectionName, currentSection);
                continue;
            }

            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                currentSection.put(key, value);
            }
        }
        return result;
    }

    private Object parseProperties(String content) throws Exception {
        var props = new Properties();
        props.load(new StringReader(content));
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            result.put(key, props.getProperty(key));
        }
        return result;
    }
}
