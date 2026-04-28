package io.github.randomcodespace.iq.detector.jvm.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;
import io.github.randomcodespace.iq.detector.DetectorContext;
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
import io.github.randomcodespace.iq.detector.ParserType;

/**
 * Detects Spring REST endpoints from mapping annotations using JavaParser AST
 * with regex fallback.
 */
@DetectorInfo(
    name = "spring_rest",
    category = "endpoints",
    description = "Detects Spring MVC REST endpoints (@GetMapping, @PostMapping, etc.)",
    parser = ParserType.JAVAPARSER,
    languages = {"java"},
    nodeKinds = {NodeKind.ENDPOINT},
    edgeKinds = {EdgeKind.EXPOSES, EdgeKind.CALLS},
    properties = {"consumes", "framework", "http_method", "method", "path", "produces"}
)
@Component
public class SpringRestDetector extends AbstractJavaParserDetector {
    private static final String PROP_CONSUMES = "consumes";
    private static final String PROP_PATH = "path";
    private static final String PROP_PRODUCES = "produces";


    // ---- Regex fallback patterns ----
    private static final Pattern MAPPING_RE = Pattern.compile(
            "@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)"
                    + "\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern VALUE_RE = Pattern.compile("(?:value\\s*=\\s*|path\\s*=\\s*)?\\{?\\s*\"([^\"]*)\"");
    private static final Pattern METHOD_ATTR_RE = Pattern.compile("method\\s*=\\s*RequestMethod\\.(\\w+)");
    private static final Pattern PRODUCES_RE = Pattern.compile("produces\\s*=\\s*\\{?\\s*\"([^\"]*)\"");
    private static final Pattern CONSUMES_RE = Pattern.compile("consumes\\s*=\\s*\\{?\\s*\"([^\"]*)\"");
    private static final Pattern JAVA_METHOD_RE = Pattern.compile(
            "(?:public|protected|private)?\\s*(?:static\\s+)?(?:[\\w<>\\[\\],\\s]+)\\s+(\\w+)\\s*\\(");

    private static final Map<String, String> MAPPING_ANNOTATIONS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    /**
     * Annotations that mark methods as non-endpoint helpers (data binders, model populators,
     * exception handlers). Methods with these annotations should NOT be detected as REST endpoints,
     * even if they also carry a mapping annotation.
     */
    private static final Set<String> NON_ENDPOINT_ANNOTATIONS = Set.of(
            "ModelAttribute", "InitBinder", "ExceptionHandler"
    );

    private static final Pattern NON_ENDPOINT_RE = Pattern.compile(
            "@(ModelAttribute|InitBinder|ExceptionHandler)\\b");

    // ---- HTTP client patterns (for CALLS edge emission) ----
    private static final Pattern REST_TEMPLATE_RE = Pattern.compile("RestTemplate");
    private static final Pattern WEB_CLIENT_RE    = Pattern.compile("WebClient");
    private static final Pattern BARE_QUOTED_RE = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern FEIGN_CLIENT_RE  = Pattern.compile(
            "@FeignClient\\s*\\(\\s*(?:name\\s*=\\s*)?[\"']([^\"']+)[\"']");

    @Override
    public String getName() {
        return "spring_rest";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        // Prefer the resolver-parsed CU when available — it has the symbol
        // solver attached, so Type.resolve() works inside the AST walk for
        // @RequestBody / @PathVariable type lifting.
        Optional<JavaResolved> resolved = ctx.resolved()
                .filter(Resolved::isAvailable)
                .filter(JavaResolved.class::isInstance)
                .map(JavaResolved.class::cast);

        Optional<CompilationUnit> cu = resolved.map(JavaResolved::cu).or(() -> parse(ctx));
        if (cu.isPresent()) {
            return detectWithAst(cu.get(), ctx, resolved);
        }
        return detectWithRegex(ctx);
    }

    // ==================== AST-based detection ====================

    private DetectorResult detectWithAst(CompilationUnit cu, DetectorContext ctx,
                                          Optional<JavaResolved> resolved) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            String className = classDecl.getNameAsString();
            String classNodeId = ctx.filePath() + ":" + className;

            // Resolve class-level @RequestMapping path
            String classBasePath = "";
            for (AnnotationExpr ann : classDecl.getAnnotations()) {
                if ("RequestMapping".equals(ann.getNameAsString())) {
                    String path = extractAnnotationPath(ann);
                    if (path != null) {
                        classBasePath = path.replaceAll("/+$", "");
                    }
                }
            }

            for (MethodDeclaration method : classDecl.getMethods()) {
                // Skip methods annotated with @ModelAttribute, @InitBinder, @ExceptionHandler
                // -- these are Spring MVC helpers, not REST endpoints
                boolean isNonEndpoint = method.getAnnotations().stream()
                        .anyMatch(a -> NON_ENDPOINT_ANNOTATIONS.contains(a.getNameAsString()));
                if (isNonEndpoint) continue;

                for (AnnotationExpr ann : method.getAnnotations()) {
                    String annName = ann.getNameAsString();

                    String httpMethod = MAPPING_ANNOTATIONS.get(annName);
                    if (httpMethod == null && "RequestMapping".equals(annName)) {
                        httpMethod = extractMethodAttr(ann);
                        if (httpMethod == null) httpMethod = "ALL";
                    }
                    if (httpMethod == null) continue;

                    String path = extractAnnotationPath(ann);
                    String produces = extractAnnotationAttr(ann, PROP_PRODUCES);
                    String consumes = extractAnnotationAttr(ann, PROP_CONSUMES);

                    String fullPath;
                    if (path != null && !path.isEmpty()) {
                        fullPath = classBasePath + "/" + path.replaceAll("^/+", "");
                    } else {
                        fullPath = classBasePath.isEmpty() ? "/" : classBasePath;
                    }
                    if (!fullPath.startsWith("/")) {
                        fullPath = "/" + fullPath;
                    }

                    String methodName = method.getNameAsString();
                    int line = ann.getBegin().map(p -> p.line).orElse(1);

                    String endpointLabel = httpMethod + " " + fullPath;
                    String endpointId = ctx.filePath() + ":" + className + ":" + methodName + ":" + httpMethod + ":" + fullPath;

                    CodeNode node = new CodeNode();
                    node.setId(endpointId);
                    node.setKind(NodeKind.ENDPOINT);
                    node.setLabel(endpointLabel);
                    node.setFqn(resolveFqn(cu, className) + "." + methodName);
                    node.setFilePath(ctx.filePath());
                    node.setLineStart(line);
                    node.getAnnotations().add("@" + annName);
                    node.getProperties().put("framework", "spring_boot");
                    node.getProperties().put("http_method", httpMethod);
                    node.getProperties().put(PROP_PATH, fullPath);
                    node.getProperties().put("method", methodName);
                    if (produces != null) node.getProperties().put(PROP_PRODUCES, produces);
                    if (consumes != null) node.getProperties().put(PROP_CONSUMES, consumes);

                    // Extract parameter annotations
                    List<Map<String, String>> params = new ArrayList<>();
                    method.getParameters().forEach(param -> {
                        param.getAnnotations().forEach(paramAnn -> {
                            String paramAnnName = paramAnn.getNameAsString();
                            if ("PathVariable".equals(paramAnnName) || "RequestParam".equals(paramAnnName)
                                    || "RequestBody".equals(paramAnnName) || "RequestHeader".equals(paramAnnName)) {
                                Map<String, String> paramInfo = new LinkedHashMap<>();
                                paramInfo.put("annotation", "@" + paramAnnName);
                                paramInfo.put("type", param.getTypeAsString());
                                paramInfo.put("name", param.getNameAsString());
                                params.add(paramInfo);
                            }
                        });
                    });
                    if (!params.isEmpty()) {
                        node.getProperties().put("parameters", params);
                    }

                    nodes.add(node);

                    CodeEdge edge = new CodeEdge();
                    edge.setId(classNodeId + "->exposes->" + endpointId);
                    edge.setKind(EdgeKind.EXPOSES);
                    edge.setSourceId(classNodeId);
                    edge.setTarget(node);
                    edges.add(edge);

                    // RESOLVED tier: emit MAPS_TO edges for @RequestBody (and
                    // @PathVariable / @RequestParam reference types) when the
                    // symbol solver can pin a fully-qualified DTO. These ride
                    // alongside the existing endpoint metadata so the SPA + MCP
                    // can navigate from endpoint → DTO without a string-match
                    // round trip through EntityLinker.
                    resolved.ifPresent(jr ->
                            addRequestBodyMapsToEdges(method, endpointId, edges));
                }
            }
        });

        // HTTP client calls → CALLS edge
        addHttpClientEdges(ctx, nodes, edges);

        return DetectorResult.of(nodes, edges);
    }

    /**
     * For each parameter annotated with {@code @RequestBody} (and the few
     * other reference-type binding annotations), try to resolve the parameter
     * type via the symbol solver. On success, emit a {@link EdgeKind#MAPS_TO}
     * edge from {@code endpointId} → {@code "*:" + simpleName} stamped with
     * {@code Confidence.RESOLVED} and a {@code target_fqn} property.
     *
     * <p>Resolution failures are silent — the request body type might be a
     * primitive (no edge needed), a third-party class missing from the
     * classpath (genuinely unresolvable), or a generic type variable. The
     * existing {@code parameters} property on the endpoint node still carries
     * the simple name for the lexical / regex tier.
     */
    private void addRequestBodyMapsToEdges(MethodDeclaration method, String endpointId,
                                            List<CodeEdge> edges) {
        for (Parameter param : method.getParameters()) {
            boolean isBindable = param.getAnnotations().stream().anyMatch(a ->
                    "RequestBody".equals(a.getNameAsString()));
            if (!isBindable) continue;
            // Only emit MAPS_TO when the parameter type is a class/interface
            // — primitive types (int, long) have no FQN and no DTO target.
            Type paramType = param.getType();
            if (!paramType.isClassOrInterfaceType()) continue;

            Optional<String> fqn = tryResolveFqn(paramType);
            if (fqn.isEmpty()) continue;

            String simpleName = paramType.asClassOrInterfaceType().getNameAsString();
            Map<String, Object> edgeProps = new LinkedHashMap<>();
            edgeProps.put("target_fqn", fqn.get());
            edgeProps.put("parameter_kind", "request_body");
            edgeProps.put("parameter_name", param.getNameAsString());

            CodeEdge mapsTo = new CodeEdge();
            mapsTo.setId(endpointId + "->maps_to->*:" + fqn.get());
            mapsTo.setKind(EdgeKind.MAPS_TO);
            mapsTo.setSourceId(endpointId);
            mapsTo.setTarget(new CodeNode("*:" + simpleName, NodeKind.CLASS, simpleName));
            mapsTo.setProperties(edgeProps);
            mapsTo.setConfidence(Confidence.RESOLVED);
            mapsTo.setSource(getName());
            edges.add(mapsTo);
        }
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

    /**
     * Extract path from a mapping annotation (value or path attribute, or bare string).
     */
    private String extractAnnotationPath(AnnotationExpr ann) {
        if (ann.isSingleMemberAnnotationExpr()) {
            return extractStringValue(ann.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (ann.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                String key = pair.getNameAsString();
                if ("value".equals(key) || "path".equals(key)) {
                    return extractStringValue(pair.getValue());
                }
            }
        }
        return null;
    }

    /**
     * Extract HTTP method from @RequestMapping(method = RequestMethod.XXX).
     */
    private String extractMethodAttr(AnnotationExpr ann) {
        if (ann.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if ("method".equals(pair.getNameAsString())) {
                    String value = pair.getValue().toString();
                    // Handle RequestMethod.GET, RequestMethod.POST, etc.
                    int dot = value.lastIndexOf('.');
                    return dot >= 0 ? value.substring(dot + 1) : value;
                }
            }
        }
        return null;
    }

    /**
     * Extract a named string attribute from a normal annotation.
     */
    private String extractAnnotationAttr(AnnotationExpr ann, String attrName) {
        if (ann.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if (attrName.equals(pair.getNameAsString())) {
                    return extractStringValue(pair.getValue());
                }
            }
        }
        return null;
    }

    /**
     * Extract a string value from an expression (handles StringLiteralExpr and arrays).
     */
    private String extractStringValue(Expression expr) {
        if (expr.isStringLiteralExpr()) {
            return expr.asStringLiteralExpr().getValue();
        }
        if (expr.isArrayInitializerExpr()) {
            for (Expression el : expr.asArrayInitializerExpr().getValues()) {
                if (el.isStringLiteralExpr()) {
                    return el.asStringLiteralExpr().getValue();
                }
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
        String classBasePath = "";
        for (int i = 0; i < lines.length; i++) {
            Matcher cm = CLASS_RE.matcher(lines[i]);
            if (cm.find()) {
                className = cm.group(1);
                for (int j = Math.max(0, i - 5); j < i; j++) {
                    Matcher mm = MAPPING_RE.matcher(lines[j]);
                    if (mm.find() && "RequestMapping".equals(mm.group(1))) {
                        String path = extractAttr(mm.group(2), VALUE_RE);
                        if (path != null) {
                            classBasePath = path.replaceAll("/+$", "");
                        }
                    }
                }
                break;
            }
        }
        if (className == null) return DetectorResult.empty();

        String classNodeId = ctx.filePath() + ":" + className;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = MAPPING_RE.matcher(lines[i]);
            if (!m.find()) continue;

            String annotationName = m.group(1);
            String attrStr = m.group(2);

            boolean isClassLevel = false;
            for (int k = i + 1; k < Math.min(i + 5, lines.length); k++) {
                String stripped = lines[k].trim();
                if (stripped.startsWith("@") || stripped.isEmpty()) continue;
                if (stripped.contains("class ") || stripped.contains("interface ")) {
                    isClassLevel = true;
                }
                break;
            }
            if (isClassLevel) continue;

            // Skip methods annotated with @ModelAttribute, @InitBinder, @ExceptionHandler
            boolean isNonEndpoint = false;
            for (int k = Math.max(0, i - 3); k < Math.min(i + 5, lines.length); k++) {
                if (k == i) continue;
                if (NON_ENDPOINT_RE.matcher(lines[k]).find()) {
                    isNonEndpoint = true;
                    break;
                }
            }
            if (isNonEndpoint) continue;

            String httpMethod = MAPPING_ANNOTATIONS.get(annotationName);
            if (httpMethod == null) {
                String extracted = extractAttr(attrStr, METHOD_ATTR_RE);
                httpMethod = extracted != null ? extracted : "ALL";
            }

            String path = extractAttr(attrStr, VALUE_RE);
            if (path == null && attrStr != null) {
                Matcher bare = BARE_QUOTED_RE.matcher(attrStr);
                if (bare.find()) path = bare.group(1);
            }
            if (path == null) path = "";

            String fullPath;
            if (!path.isEmpty()) {
                fullPath = classBasePath + "/" + path.replaceAll("^/+", "");
            } else {
                fullPath = classBasePath.isEmpty() ? "/" : classBasePath;
            }
            if (!fullPath.startsWith("/")) fullPath = "/" + fullPath;

            String produces = extractAttr(attrStr, PRODUCES_RE);
            String consumes = extractAttr(attrStr, CONSUMES_RE);

            String methodName = null;
            for (int k = i + 1; k < Math.min(i + 5, lines.length); k++) {
                Matcher mm = JAVA_METHOD_RE.matcher(lines[k]);
                if (mm.find()) { methodName = mm.group(1); break; }
            }

            String endpointLabel = httpMethod + " " + fullPath;
            String endpointId = ctx.filePath() + ":" + className + ":" + (methodName != null ? methodName : "unknown") + ":" + httpMethod + ":" + fullPath;

            CodeNode node = new CodeNode();
            node.setId(endpointId);
            node.setKind(NodeKind.ENDPOINT);
            node.setLabel(endpointLabel);
            node.setFqn(methodName != null ? className + "." + methodName : className);
            node.setFilePath(ctx.filePath());
            node.setLineStart(i + 1);
            node.getAnnotations().add("@" + annotationName);
            node.getProperties().put("framework", "spring_boot");
            node.getProperties().put("http_method", httpMethod);
            node.getProperties().put(PROP_PATH, fullPath);
            if (produces != null) node.getProperties().put(PROP_PRODUCES, produces);
            if (consumes != null) node.getProperties().put(PROP_CONSUMES, consumes);
            nodes.add(node);

            CodeEdge edge = new CodeEdge();
            edge.setId(classNodeId + "->exposes->" + endpointId);
            edge.setKind(EdgeKind.EXPOSES);
            edge.setSourceId(classNodeId);
            edge.setTarget(node);
            edges.add(edge);
        }

        // HTTP client calls → CALLS edge
        addHttpClientEdges(ctx, nodes, edges);

        return DetectorResult.of(nodes, edges);
    }

    private static String extractAttr(String attrStr, Pattern pattern) {
        if (attrStr == null) return null;
        Matcher m = pattern.matcher(attrStr);
        return m.find() ? m.group(1) : null;
    }

    // ==================== HTTP client / InfrastructureRegistry helpers ====================

    private static void addHttpClientEdges(DetectorContext ctx,
            List<CodeNode> nodes, List<CodeEdge> edges) {
        String text = ctx.content();
        boolean hasRestTemplate = REST_TEMPLATE_RE.matcher(text).find();
        boolean hasWebClient    = WEB_CLIENT_RE.matcher(text).find();
        Matcher feignMatcher    = FEIGN_CLIENT_RE.matcher(text);
        boolean hasFeignClient  = feignMatcher.find();
        if (!hasRestTemplate && !hasWebClient && !hasFeignClient) return;

        io.github.randomcodespace.iq.analyzer.InfrastructureRegistry registry = ctx.registry();
        String clientType = hasRestTemplate ? "RestTemplate"
                          : hasWebClient    ? "WebClient"
                          : "FeignClient";

        // Try to match FeignClient name against registry external APIs
        String targetId;
        String targetLabel;
        if (hasFeignClient && registry != null) {
            String feignName = feignMatcher.group(1);
            io.github.randomcodespace.iq.analyzer.InfraEndpoint matched =
                    registry.getExternalApis().values().stream()
                            .filter(e -> feignName.equalsIgnoreCase(e.name()))
                            .findFirst().orElse(null);
            if (matched != null) {
                targetId = "infra:" + matched.id();
                targetLabel = matched.name();
            } else {
                targetId = "external:" + feignName;
                targetLabel = feignName;
            }
        } else if (registry != null && !registry.getExternalApis().isEmpty()) {
            io.github.randomcodespace.iq.analyzer.InfraEndpoint api =
                    registry.getExternalApis().values().iterator().next();
            targetId = "infra:" + api.id();
            targetLabel = api.name();
        } else {
            targetId = "external:unknown";
            targetLabel = "External API";
        }

        if (nodes.stream().noneMatch(n -> targetId.equals(n.getId()))) {
            CodeNode apiNode = new CodeNode(targetId, NodeKind.ENDPOINT, targetLabel);
            apiNode.getProperties().put("type", "external_api");
            nodes.add(apiNode);
        }

        String sourceId = ctx.filePath();
        CodeNode targetRef = nodes.stream()
                .filter(n -> targetId.equals(n.getId()))
                .findFirst()
                .orElseGet(() -> new CodeNode(targetId, NodeKind.ENDPOINT, targetLabel));
        CodeEdge edge = new CodeEdge();
        edge.setId(sourceId + "->calls->" + targetId);
        edge.setKind(EdgeKind.CALLS);
        edge.setSourceId(sourceId);
        edge.setTarget(targetRef);
        edge.getProperties().put("client_type", clientType);
        edges.add(edge);
    }
}
