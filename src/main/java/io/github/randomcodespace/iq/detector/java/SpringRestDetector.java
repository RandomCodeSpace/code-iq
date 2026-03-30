package io.github.randomcodespace.iq.detector.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
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
    edgeKinds = {EdgeKind.EXPOSES},
    properties = {"consumes", "http_method", "method", "path", "produces"}
)
@Component
public class SpringRestDetector extends AbstractJavaParserDetector {

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
                for (AnnotationExpr ann : method.getAnnotations()) {
                    String annName = ann.getNameAsString();

                    String httpMethod = MAPPING_ANNOTATIONS.get(annName);
                    if (httpMethod == null && "RequestMapping".equals(annName)) {
                        httpMethod = extractMethodAttr(ann);
                        if (httpMethod == null) httpMethod = "GET";
                    }
                    if (httpMethod == null) continue;

                    String path = extractAnnotationPath(ann);
                    String produces = extractAnnotationAttr(ann, "produces");
                    String consumes = extractAnnotationAttr(ann, "consumes");

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
                    node.getProperties().put("http_method", httpMethod);
                    node.getProperties().put("path", fullPath);
                    node.getProperties().put("method", methodName);
                    if (produces != null) node.getProperties().put("produces", produces);
                    if (consumes != null) node.getProperties().put("consumes", consumes);

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
                }
            }
        });

        return DetectorResult.of(nodes, edges);
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

            String httpMethod = MAPPING_ANNOTATIONS.get(annotationName);
            if (httpMethod == null) {
                String extracted = extractAttr(attrStr, METHOD_ATTR_RE);
                httpMethod = extracted != null ? extracted : "GET";
            }

            String path = extractAttr(attrStr, VALUE_RE);
            if (path == null && attrStr != null) {
                Matcher bare = Pattern.compile("\"([^\"]*)\"").matcher(attrStr);
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
            node.getProperties().put("http_method", httpMethod);
            node.getProperties().put("path", fullPath);
            if (produces != null) node.getProperties().put("produces", produces);
            if (consumes != null) node.getProperties().put("consumes", consumes);
            nodes.add(node);

            CodeEdge edge = new CodeEdge();
            edge.setId(classNodeId + "->exposes->" + endpointId);
            edge.setKind(EdgeKind.EXPOSES);
            edge.setSourceId(classNodeId);
            edge.setTarget(node);
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }

    private static String extractAttr(String attrStr, Pattern pattern) {
        if (attrStr == null) return null;
        Matcher m = pattern.matcher(attrStr);
        return m.find() ? m.group(1) : null;
    }
}
