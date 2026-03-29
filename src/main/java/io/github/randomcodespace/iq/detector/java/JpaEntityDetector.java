package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects JPA entities and their relationships.
 */
@Component
public class JpaEntityDetector extends AbstractRegexDetector {

    private static final Pattern ENTITY_RE = Pattern.compile("@Entity");
    private static final Pattern TABLE_RE = Pattern.compile("@Table\\s*\\(\\s*(?:name\\s*=\\s*)?\"(\\w+)\"");
    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern COLUMN_RE = Pattern.compile("@Column\\s*\\(([^)]*)\\)");
    private static final Pattern COLUMN_NAME_RE = Pattern.compile("name\\s*=\\s*\"(\\w+)\"");
    private static final Pattern FIELD_RE = Pattern.compile("(?:private|protected|public)\\s+([\\w<>,\\s]+)\\s+(\\w+)\\s*[;=]");
    private static final Pattern RELATIONSHIP_RE = Pattern.compile("@(OneToMany|ManyToOne|OneToOne|ManyToMany)");
    private static final Pattern TARGET_ENTITY_RE = Pattern.compile("targetEntity\\s*=\\s*(\\w+)\\.class");
    private static final Pattern MAPPED_BY_RE = Pattern.compile("mappedBy\\s*=\\s*\"(\\w+)\"");
    private static final Pattern GENERIC_TYPE_RE = Pattern.compile("<(\\w+)>");

    private static final Map<String, String> RELATIONSHIP_ANNOTATIONS = Map.of(
            "OneToMany", "one_to_many",
            "ManyToOne", "many_to_one",
            "OneToOne", "one_to_one",
            "ManyToMany", "many_to_many"
    );

    @Override
    public String getName() {
        return "jpa_entity";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || !ENTITY_RE.matcher(text).find()) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Find class name
        String className = null;
        int classLine = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher cm = CLASS_RE.matcher(lines[i]);
            if (cm.find()) {
                className = cm.group(1);
                classLine = i + 1;
                break;
            }
        }

        if (className == null) {
            return DetectorResult.empty();
        }

        // Extract table name
        Matcher tableMatch = TABLE_RE.matcher(text);
        String tableName = tableMatch.find() ? tableMatch.group(1) : className.toLowerCase();

        // Extract columns
        List<Map<String, String>> columns = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher colMatch = COLUMN_RE.matcher(lines[i]);
            if (colMatch.find()) {
                Matcher colNameMatch = COLUMN_NAME_RE.matcher(colMatch.group(1));
                for (int k = i + 1; k < Math.min(i + 3, lines.length); k++) {
                    Matcher fm = FIELD_RE.matcher(lines[k]);
                    if (fm.find()) {
                        String colName = colNameMatch.find() ? colNameMatch.group(1) : fm.group(2);
                        columns.add(Map.of("name", colName, "field", fm.group(2), "type", fm.group(1).trim()));
                        break;
                    }
                }
            }
        }

        String entityId = ctx.filePath() + ":" + className;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("table_name", tableName);
        if (!columns.isEmpty()) {
            properties.put("columns", columns);
        }

        CodeNode node = new CodeNode();
        node.setId(entityId);
        node.setKind(NodeKind.ENTITY);
        node.setLabel(className + " (" + tableName + ")");
        node.setFqn(className);
        node.setFilePath(ctx.filePath());
        node.setLineStart(classLine);
        node.setAnnotations(new ArrayList<>(List.of("@Entity")));
        node.setProperties(properties);
        nodes.add(node);

        // Extract relationships
        for (int i = 0; i < lines.length; i++) {
            Matcher relMatch = RELATIONSHIP_RE.matcher(lines[i]);
            if (!relMatch.find()) {
                continue;
            }

            String relType = RELATIONSHIP_ANNOTATIONS.get(relMatch.group(1));

            String targetEntity = null;
            Matcher targetMatch = TARGET_ENTITY_RE.matcher(lines[i]);
            if (targetMatch.find()) {
                targetEntity = targetMatch.group(1);
            } else {
                for (int k = i + 1; k < Math.min(i + 4, lines.length); k++) {
                    Matcher fm = FIELD_RE.matcher(lines[k]);
                    if (fm.find()) {
                        String fieldType = fm.group(1).trim();
                        Matcher gm = GENERIC_TYPE_RE.matcher(fieldType);
                        if (gm.find()) {
                            targetEntity = gm.group(1);
                        } else {
                            String[] parts = fieldType.split("\\s+");
                            targetEntity = parts[parts.length - 1];
                        }
                        break;
                    }
                }
            }

            if (targetEntity != null) {
                Matcher mappedBy = MAPPED_BY_RE.matcher(lines[i]);
                Map<String, Object> edgeProps = new LinkedHashMap<>();
                edgeProps.put("relationship_type", relType);
                if (mappedBy.find()) {
                    edgeProps.put("mapped_by", mappedBy.group(1));
                }

                CodeEdge edge = new CodeEdge();
                edge.setId(entityId + "->maps_to->*:" + targetEntity);
                edge.setKind(EdgeKind.MAPS_TO);
                edge.setSourceId(entityId);
                CodeNode targetRef = new CodeNode("*:" + targetEntity, NodeKind.ENTITY, targetEntity);
                edge.setTarget(targetRef);
                edge.setProperties(edgeProps);
                edges.add(edge);
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
