package io.github.randomcodespace.iq.detector.jvm.java;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;
import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorDbHelper;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import io.github.randomcodespace.iq.intelligence.resolver.java.JavaResolved;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.Confidence;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;

/**
 * Detects Spring Data repository interfaces.
 */
@DetectorInfo(
    name = "spring_repository",
    category = "entities",
    description = "Detects Spring Data repositories and custom query methods",
    languages = {"java"},
    nodeKinds = {NodeKind.ENTITY, NodeKind.REPOSITORY, NodeKind.DATABASE_CONNECTION},
    edgeKinds = {EdgeKind.QUERIES, EdgeKind.CONNECTS_TO},
    properties = {"custom_queries", "framework", "method"}
)
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
        properties.put("framework", "spring_boot");
        if (parentRepo != null) {
            properties.put("extends", parentRepo);
        }
        if (entityType != null) {
            properties.put("entity_type", entityType);
        }

        // RESOLVED tier: when ctx.resolved() carries a JavaResolved we look up
        // the entity type's fully-qualified name via the symbol solver. The
        // FQN rides on both the repo node (entity_fqn) and the QUERIES edge
        // (target_fqn) so consumers (EntityLinker, query routing, the SPA)
        // can pick the unambiguous reference when available.
        Optional<JavaResolved> resolved = ctx.resolved()
                .filter(Resolved::isAvailable)
                .filter(JavaResolved.class::isInstance)
                .map(JavaResolved.class::cast);
        final String resolvedInterfaceName = interfaceName; // effectively-final capture for the lambda
        Optional<String> entityFqn = (entityType != null)
                ? resolved.flatMap(jr -> resolveEntityFqn(jr, resolvedInterfaceName))
                : Optional.empty();
        entityFqn.ifPresent(fqn -> properties.put("entity_fqn", fqn));

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
            if (entityFqn.isPresent()) {
                Map<String, Object> edgeProps = new LinkedHashMap<>();
                edgeProps.put("target_fqn", entityFqn.get());
                edge.setProperties(edgeProps);
                edge.setConfidence(Confidence.RESOLVED);
                edge.setSource(getName());
            }
            edges.add(edge);
        }
        DetectorDbHelper.addDbEdge(repoId, ctx.registry(), nodes, edges);

        return DetectorResult.of(nodes, edges);
    }

    /**
     * Use the resolver-attached symbol solver to find the entity type's FQN.
     * Walks {@link JavaResolved#cu()}'s class hierarchy: locate the interface
     * declaration matching {@code interfaceName}, take its first extended type
     * (e.g. {@code JpaRepository<User, Long>}), then resolve the first type
     * argument ({@code User}) to a fully-qualified name.
     *
     * <p>Returns empty on any solver failure — graceful fallback to the
     * regex-extracted simple-name target.
     */
    private Optional<String> resolveEntityFqn(JavaResolved jr, String interfaceName) {
        return jr.cu().findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(d -> interfaceName.equals(d.getNameAsString()))
                .findFirst()
                .flatMap(this::firstTypeArgOfFirstParent)
                .flatMap(RepositoryDetector::tryResolveFqn);
    }

    /** Take the first type argument of the first extended/implemented type, if any. */
    private Optional<Type> firstTypeArgOfFirstParent(ClassOrInterfaceDeclaration decl) {
        for (ClassOrInterfaceType parent : decl.getExtendedTypes()) {
            var typeArgs = parent.getTypeArguments();
            if (typeArgs.isPresent() && !typeArgs.get().isEmpty()) {
                return Optional.of(typeArgs.get().get(0));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> tryResolveFqn(Type type) {
        try {
            ResolvedType rt = type.resolve();
            if (rt.isReferenceType()) {
                return Optional.of(rt.asReferenceType().getQualifiedName());
            }
            return Optional.of(rt.describe());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
