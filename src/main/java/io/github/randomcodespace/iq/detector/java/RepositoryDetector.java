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
 * Detects Spring Data repository interfaces.
 */
@Component
public class RepositoryDetector extends AbstractRegexDetector {

    private static final Pattern REPO_EXTENDS_RE = Pattern.compile(
            "interface\\s+(\\w+)\\s+extends\\s+((?:JpaRepository|CrudRepository|"
                    + "PagingAndSortingRepository|ReactiveCrudRepository|"
                    + "MongoRepository|ElasticsearchRepository|"
                    + "R2dbcRepository|JpaSpecificationExecutor)\\w*)"
                    + "(?:<\\s*(\\w+)\\s*,\\s*[\\w<>]+\\s*>)?"
    );
    private static final Pattern REPOSITORY_ANNO_RE = Pattern.compile("@Repository");
    private static final Pattern INTERFACE_RE = Pattern.compile("interface\\s+(\\w+)");
    private static final Pattern GENERIC_PARAMS_RE = Pattern.compile("<\\s*(\\w+)\\s*,");
    private static final Pattern QUERY_RE = Pattern.compile("@Query\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern METHOD_RE = Pattern.compile("(?:public\\s+)?(?:[\\w<>\\[\\],?\\s]+)\\s+(\\w+)\\s*\\(");

    @Override
    public String getName() {
        return "spring_repository";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }

        boolean hasRepoAnnotation = REPOSITORY_ANNO_RE.matcher(text).find();
        Matcher extendsMatch = REPO_EXTENDS_RE.matcher(text);
        boolean hasExtends = extendsMatch.find();

        if (!hasExtends && !hasRepoAnnotation) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String interfaceName = null;
        String entityType = null;
        String parentRepo = null;
        int interfaceLine = 0;

        if (hasExtends) {
            interfaceName = extendsMatch.group(1);
            parentRepo = extendsMatch.group(2);
            entityType = extendsMatch.group(3);
            for (int i = 0; i < lines.length; i++) {
                if (interfaceName != null && lines[i].contains(interfaceName) && lines[i].contains("interface")) {
                    interfaceLine = i + 1;
                    break;
                }
            }
        } else {
            for (int i = 0; i < lines.length; i++) {
                Matcher im = INTERFACE_RE.matcher(lines[i]);
                if (im.find()) {
                    interfaceName = im.group(1);
                    interfaceLine = i + 1;
                    Matcher gm = GENERIC_PARAMS_RE.matcher(lines[i]);
                    if (gm.find()) {
                        entityType = gm.group(1);
                    }
                    break;
                }
            }
        }

        if (interfaceName == null) {
            return DetectorResult.empty();
        }

        String repoId = ctx.filePath() + ":" + interfaceName;
        Map<String, Object> properties = new LinkedHashMap<>();
        if (parentRepo != null) {
            properties.put("extends", parentRepo);
        }
        if (entityType != null) {
            properties.put("entity_type", entityType);
        }

        // Extract @Query methods
        List<Map<String, String>> customQueries = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher qm = QUERY_RE.matcher(lines[i]);
            if (qm.find()) {
                String queryStr = qm.group(1);
                String methodName = null;
                for (int k = i + 1; k < Math.min(i + 4, lines.length); k++) {
                    Matcher mm = METHOD_RE.matcher(lines[k]);
                    if (mm.find()) {
                        methodName = mm.group(1);
                        break;
                    }
                }
                customQueries.add(Map.of("query", queryStr, "method", methodName != null ? methodName : "unknown"));
            }
        }
        if (!customQueries.isEmpty()) {
            properties.put("custom_queries", customQueries);
        }

        CodeNode node = new CodeNode();
        node.setId(repoId);
        node.setKind(NodeKind.REPOSITORY);
        node.setLabel(interfaceName);
        node.setFqn(interfaceName);
        node.setFilePath(ctx.filePath());
        node.setLineStart(interfaceLine);
        node.setAnnotations(hasRepoAnnotation ? new ArrayList<>(List.of("@Repository")) : new ArrayList<>());
        node.setProperties(properties);
        nodes.add(node);

        if (entityType != null) {
            CodeEdge edge = new CodeEdge();
            edge.setId(repoId + "->queries->*:" + entityType);
            edge.setKind(EdgeKind.QUERIES);
            edge.setSourceId(repoId);
            CodeNode targetRef = new CodeNode("*:" + entityType, NodeKind.ENTITY, entityType);
            edge.setTarget(targetRef);
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }
}
