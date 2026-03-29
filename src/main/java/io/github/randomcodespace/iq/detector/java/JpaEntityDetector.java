package io.github.randomcodespace.iq.detector.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
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
 * Detects JPA entities and their relationships using JavaParser AST with regex fallback.
 */
@Component
public class JpaEntityDetector extends AbstractJavaParserDetector {

    private static final Set<String> RELATIONSHIP_ANNOTATIONS = Set.of(
            "OneToMany", "ManyToOne", "OneToOne", "ManyToMany");
    private static final Map<String, String> RELATIONSHIP_TYPES = Map.of(
            "OneToMany", "one_to_many",
            "ManyToOne", "many_to_one",
            "OneToOne", "one_to_one",
            "ManyToMany", "many_to_many");

    // ---- Regex fallback patterns ----
    private static final Pattern ENTITY_RE = Pattern.compile("@Entity");
    private static final Pattern TABLE_RE = Pattern.compile("@Table\\s*\\(\\s*(?:name\\s*=\\s*)?\"(\\w+)\"");
    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern COLUMN_RE = Pattern.compile("@Column\\s*\\(([^)]*)\\)");
    private static final Pattern COLUMN_NAME_RE = Pattern.compile("name\\s*=\\s*\"(\\w+)\"");
    private static final Pattern FIELD_RE = Pattern.compile("(?:private|protected|public)\\s+([\\w<>,\\s]+)\\s+(\\w+)\\s*[;=]");
    private static final Pattern RELATIONSHIP_REGEX = Pattern.compile("@(OneToMany|ManyToOne|OneToOne|ManyToMany)");
    private static final Pattern TARGET_ENTITY_RE = Pattern.compile("targetEntity\\s*=\\s*(\\w+)\\.class");
    private static final Pattern MAPPED_BY_RE = Pattern.compile("mappedBy\\s*=\\s*\"(\\w+)\"");
    private static final Pattern GENERIC_TYPE_RE = Pattern.compile("<(\\w+)>");

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
        if (text == null || !text.contains("@Entity")) return DetectorResult.empty();

        Optional<CompilationUnit> cu = parse(ctx);
        if (cu.isPresent()) {
            return detectWithAst(cu.get(), ctx);
        }
        return detectWithRegex(ctx);
    }

    // ==================== AST-based detection ====================

    private DetectorResult detectWithAst(CompilationUnit cu, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            // Only process @Entity annotated classes
            boolean isEntity = classDecl.getAnnotations().stream()
                    .anyMatch(a -> "Entity".equals(a.getNameAsString()));
            if (!isEntity) return;

            String className = classDecl.getNameAsString();
            String fqn = resolveFqn(cu, className);
            int classLine = classDecl.getBegin().map(p -> p.line).orElse(1);

            // Extract table name from @Table annotation
            String tableName = className.toLowerCase();
            for (AnnotationExpr ann : classDecl.getAnnotations()) {
                if ("Table".equals(ann.getNameAsString())) {
                    String name = extractAnnotationStringAttr(ann, "name");
                    if (name == null) {
                        // Try bare value
                        name = extractAnnotationValue(ann);
                    }
                    if (name != null) tableName = name;
                }
            }

            // Extract columns from fields
            List<Map<String, String>> columns = new ArrayList<>();
            for (FieldDeclaration field : classDecl.getFields()) {
                for (VariableDeclarator var : field.getVariables()) {
                    String fieldName = var.getNameAsString();
                    String fieldType = var.getTypeAsString();

                    // Check for @Column annotation
                    for (AnnotationExpr ann : field.getAnnotations()) {
                        if ("Column".equals(ann.getNameAsString())) {
                            String colName = extractAnnotationStringAttr(ann, "name");
                            if (colName == null) colName = fieldName;
                            columns.add(Map.of("name", colName, "field", fieldName, "type", fieldType));
                        } else if ("Id".equals(ann.getNameAsString())) {
                            columns.add(Map.of("name", fieldName, "field", fieldName, "type", fieldType));
                        }
                    }
                }
            }

            String entityId = ctx.filePath() + ":" + className;
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("table_name", tableName);
            if (!columns.isEmpty()) properties.put("columns", columns);

            CodeNode node = new CodeNode();
            node.setId(entityId);
            node.setKind(NodeKind.ENTITY);
            node.setLabel(className + " (" + tableName + ")");
            node.setFqn(fqn);
            node.setFilePath(ctx.filePath());
            node.setLineStart(classLine);
            node.setAnnotations(new ArrayList<>(List.of("@Entity")));
            node.setProperties(properties);
            nodes.add(node);

            // Extract relationship edges from fields
            for (FieldDeclaration field : classDecl.getFields()) {
                for (AnnotationExpr ann : field.getAnnotations()) {
                    String annName = ann.getNameAsString();
                    if (!RELATIONSHIP_ANNOTATIONS.contains(annName)) continue;

                    String relType = RELATIONSHIP_TYPES.get(annName);

                    // Resolve target entity
                    String targetEntity = extractAnnotationStringAttr(ann, "targetEntity");
                    if (targetEntity != null && targetEntity.endsWith(".class")) {
                        targetEntity = targetEntity.replace(".class", "");
                    }

                    if (targetEntity == null) {
                        // Try to resolve from field type / generic type argument
                        for (VariableDeclarator var : field.getVariables()) {
                            Type type = var.getType();
                            if (type.isClassOrInterfaceType()) {
                                ClassOrInterfaceType cit = type.asClassOrInterfaceType();
                                if (cit.getTypeArguments().isPresent()) {
                                    // Generic type like List<Order> -> Order
                                    var typeArgs = cit.getTypeArguments().get();
                                    if (!typeArgs.isEmpty()) {
                                        targetEntity = typeArgs.get(0).asString();
                                    }
                                } else {
                                    targetEntity = cit.getNameAsString();
                                }
                            }
                            break;
                        }
                    }

                    if (targetEntity != null) {
                        String mappedBy = extractAnnotationStringAttr(ann, "mappedBy");
                        Map<String, Object> edgeProps = new LinkedHashMap<>();
                        edgeProps.put("relationship_type", relType);
                        if (mappedBy != null) edgeProps.put("mapped_by", mappedBy);

                        CodeEdge edge = new CodeEdge();
                        edge.setId(entityId + "->maps_to->*:" + targetEntity);
                        edge.setKind(EdgeKind.MAPS_TO);
                        edge.setSourceId(entityId);
                        edge.setTarget(new CodeNode("*:" + targetEntity, NodeKind.ENTITY, targetEntity));
                        edge.setProperties(edgeProps);
                        edges.add(edge);
                    }
                }
            }
        });

        return DetectorResult.of(nodes, edges);
    }

    private String extractAnnotationStringAttr(AnnotationExpr ann, String attrName) {
        if (ann.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if (attrName.equals(pair.getNameAsString())) {
                    if (pair.getValue().isStringLiteralExpr()) {
                        return pair.getValue().asStringLiteralExpr().getValue();
                    }
                    // Handle Foo.class expressions
                    return pair.getValue().toString();
                }
            }
        }
        return null;
    }

    private String extractAnnotationValue(AnnotationExpr ann) {
        if (ann.isSingleMemberAnnotationExpr()) {
            var val = ann.asSingleMemberAnnotationExpr().getMemberValue();
            if (val.isStringLiteralExpr()) {
                return val.asStringLiteralExpr().getValue();
            }
        }
        return null;
    }

    // ==================== Regex fallback ====================

    private DetectorResult detectWithRegex(DetectorContext ctx) {
        String text = ctx.content();
        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String className = null;
        int classLine = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher cm = CLASS_RE.matcher(lines[i]);
            if (cm.find()) { className = cm.group(1); classLine = i + 1; break; }
        }
        if (className == null) return DetectorResult.empty();

        Matcher tableMatch = TABLE_RE.matcher(text);
        String tableName = tableMatch.find() ? tableMatch.group(1) : className.toLowerCase();

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
        if (!columns.isEmpty()) properties.put("columns", columns);

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

        for (int i = 0; i < lines.length; i++) {
            Matcher relMatch = RELATIONSHIP_REGEX.matcher(lines[i]);
            if (!relMatch.find()) continue;

            String relType = RELATIONSHIP_TYPES.get(relMatch.group(1));
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
                if (mappedBy.find()) edgeProps.put("mapped_by", mappedBy.group(1));

                CodeEdge edge = new CodeEdge();
                edge.setId(entityId + "->maps_to->*:" + targetEntity);
                edge.setKind(EdgeKind.MAPS_TO);
                edge.setSourceId(entityId);
                edge.setTarget(new CodeNode("*:" + targetEntity, NodeKind.ENTITY, targetEntity));
                edge.setProperties(edgeProps);
                edges.add(edge);
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
