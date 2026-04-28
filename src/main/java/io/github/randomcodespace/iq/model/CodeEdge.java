package io.github.randomcodespace.iq.model;

import io.github.randomcodespace.iq.config.MapToJsonConverter;
import org.springframework.data.neo4j.core.convert.ConvertWith;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An edge (relationship) in the Code IQ knowledge graph.
 */
@RelationshipProperties
public class CodeEdge {

    @Id
    @GeneratedValue
    private Long internalId;

    private String id;

    @ConvertWith(converter = EdgeKindConverter.class)
    private EdgeKind kind;

    private String sourceId;

    @TargetNode
    private CodeNode target;

    @ConvertWith(converter = MapToJsonConverter.class)
    private Map<String, Object> properties = new HashMap<>();

    /**
     * Confidence in this edge's existence and target accuracy. Defaults to
     * {@link Confidence#LEXICAL} for backward compatibility with edges
     * persisted before this field existed.
     */
    @ConvertWith(converter = ConfidenceConverter.class)
    private Confidence confidence = Confidence.LEXICAL;

    /**
     * Detector class simple name that emitted this edge, e.g.
     * {@code "SpringServiceDetector"}. Stamped by detector base classes.
     */
    private String source;

    public CodeEdge() {
    }

    public CodeEdge(String id, EdgeKind kind, String sourceId, CodeNode target) {
        this.id = id;
        this.kind = kind;
        this.sourceId = sourceId;
        this.target = target;
    }

    // --- Getters and Setters ---

    public Long getInternalId() {
        return internalId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public EdgeKind getKind() {
        return kind;
    }

    public void setKind(EdgeKind kind) {
        this.kind = kind;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public CodeNode getTarget() {
        return target;
    }

    public void setTarget(CodeNode target) {
        this.target = target;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    /**
     * @return confidence stamped by the detector. Never {@code null} — falls
     *         back to {@link Confidence#LEXICAL} for edges loaded before this
     *         field existed.
     */
    public Confidence getConfidence() {
        return confidence != null ? confidence : Confidence.LEXICAL;
    }

    /**
     * Set confidence. {@code null} is normalized to {@link Confidence#LEXICAL}
     * so the field is never null at rest.
     */
    public void setConfidence(Confidence confidence) {
        this.confidence = confidence != null ? confidence : Confidence.LEXICAL;
    }

    /**
     * @return the simple class name of the detector that emitted this edge,
     *         or {@code null} if the edge was constructed bare.
     */
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CodeEdge codeEdge = (CodeEdge) o;
        return Objects.equals(id, codeEdge.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CodeEdge{id='%s', kind=%s, source='%s'}".formatted(id, kind, sourceId);
    }
}
