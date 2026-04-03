package io.github.randomcodespace.iq.model;

import io.github.randomcodespace.iq.config.MapToJsonConverter;
import org.springframework.data.neo4j.core.convert.ConvertWith;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.schema.Relationship.Direction;

import io.github.randomcodespace.iq.intelligence.Provenance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A node in the OSSCodeIQ knowledge graph.
 * Stored as a Neo4j node with label "CodeNode".
 */
@Node("CodeNode")
public class CodeNode {

    @Id
    private String id;

    @ConvertWith(converter = NodeKindConverter.class)
    private NodeKind kind;

    private String label;

    private String fqn;

    private String module;

    private String filePath;

    private Integer lineStart;

    private Integer lineEnd;

    /** Layer classification: frontend, backend, infra, shared, unknown. */
    private String layer;

    private List<String> annotations = new ArrayList<>();

    @ConvertWith(converter = MapToJsonConverter.class)
    private Map<String, Object> properties = new HashMap<>();

    @Relationship(type = "RELATES_TO", direction = Direction.OUTGOING)
    private List<CodeEdge> edges = new ArrayList<>();

    public CodeNode() {
    }

    public CodeNode(String id, NodeKind kind, String label) {
        this.id = id;
        this.kind = kind;
        this.label = label;
    }

    // --- Getters and Setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public NodeKind getKind() {
        return kind;
    }

    public void setKind(NodeKind kind) {
        this.kind = kind;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getFqn() {
        return fqn;
    }

    public void setFqn(String fqn) {
        this.fqn = fqn;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Integer getLineStart() {
        return lineStart;
    }

    public void setLineStart(Integer lineStart) {
        this.lineStart = lineStart;
    }

    public Integer getLineEnd() {
        return lineEnd;
    }

    public void setLineEnd(Integer lineEnd) {
        this.lineEnd = lineEnd;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        this.layer = layer;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(List<String> annotations) {
        this.annotations = annotations;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    public List<CodeEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<CodeEdge> edges) {
        this.edges = edges;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CodeNode codeNode = (CodeNode) o;
        return Objects.equals(id, codeNode.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    // --- Provenance helpers ---

    /**
     * Write provenance into this node's properties map using {@code prov_*} keys.
     * Overwrites any existing provenance.
     */
    public void setProvenance(Provenance provenance) {
        if (provenance != null) {
            properties.putAll(provenance.toProperties());
        }
    }

    /**
     * Read provenance from this node's properties map.
     * Returns null if no provenance keys are present.
     */
    public Provenance getProvenance() {
        return Provenance.fromProperties(properties);
    }

    @Override
    public String toString() {
        return "CodeNode{id='%s', kind=%s, label='%s'}".formatted(id, kind, label);
    }
}
