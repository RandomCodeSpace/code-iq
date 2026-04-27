package io.github.randomcodespace.iq.detector;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.Confidence;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for structured data detectors (YAML/JSON/XML).
 * Provides defensive access methods for navigating parsed data structures.
 */
public abstract class AbstractStructuredDetector implements Detector {

    /**
     * Structured (YAML/JSON/TOML/properties) parsing produces a parsed shape, not
     * just a regex match — confidence floor is {@link Confidence#SYNTACTIC}.
     */
    @Override
    public Confidence defaultConfidence() {
        return Confidence.SYNTACTIC;
    }

    /**
     * Safely cast an object to {@code Map<String, Object>}.
     * Returns an empty map if the object is not a map.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    /**
     * Get a nested map from a container by key.
     * Returns an empty map if the key is missing or the value is not a map.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> getMap(Object container, String key) {
        if (container instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof Map<?, ?> nested) {
                return (Map<String, Object>) nested;
            }
        }
        return Collections.emptyMap();
    }

    /**
     * Get a list from a container by key.
     * Returns an empty list if the key is missing or the value is not a list.
     */
    @SuppressWarnings("unchecked")
    protected List<Object> getList(Object container, String key) {
        if (container instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof List<?> list) {
                return (List<Object>) list;
            }
        }
        return Collections.emptyList();
    }

    /**
     * Get a string from a container by key.
     * Returns null if the key is missing or the value is not a string.
     */
    protected String getString(Object container, String key) {
        if (container instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof String s) {
                return s;
            }
        }
        return null;
    }

    /**
     * Get a string from a container by key, with a default value.
     */
    protected String getStringOrDefault(Object container, String key, String defaultValue) {
        String value = getString(container, key);
        return value != null ? value : defaultValue;
    }

    /**
     * Get an integer from a container by key, with a default value.
     * Handles both Integer and Number types.
     */
    protected int getInt(Object container, String key, int defaultValue) {
        if (container instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
        }
        return defaultValue;
    }

    /**
     * Build a CONFIG_FILE node for the given context and format.
     * The returned node is NOT added to any list — callers must do that themselves.
     */
    protected CodeNode buildFileNode(DetectorContext ctx, String format) {
        String fp = ctx.filePath();
        String fileId = format + ":" + fp;
        CodeNode fileNode = new CodeNode(fileId, NodeKind.CONFIG_FILE, fp);
        fileNode.setFqn(fp);
        fileNode.setModule(ctx.moduleName());
        fileNode.setFilePath(fp);
        fileNode.setLineStart(1);
        fileNode.setProperties(new HashMap<>(Map.of("format", format)));
        return fileNode;
    }

    /**
     * Build a CONFIG_KEY node and a CONTAINS edge from {@code fileId} to it,
     * appending both to the supplied lists.
     */
    protected void addKeyNode(String fileId, String fp, String key, String format,
            DetectorContext ctx, List<CodeNode> nodes, List<CodeEdge> edges) {
        String keyId = format + ":" + fp + ":" + key;
        CodeNode keyNode = new CodeNode(keyId, NodeKind.CONFIG_KEY, key);
        keyNode.setFqn(fp + ":" + key);
        keyNode.setModule(ctx.moduleName());
        keyNode.setFilePath(fp);
        nodes.add(keyNode);
        CodeEdge edge = new CodeEdge();
        edge.setId(fileId + "->" + keyId);
        edge.setKind(EdgeKind.CONTAINS);
        edge.setSourceId(fileId);
        edge.setTarget(new CodeNode(keyId, null, null));
        edges.add(edge);
    }
}
