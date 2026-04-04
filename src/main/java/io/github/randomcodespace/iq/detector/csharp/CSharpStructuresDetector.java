package io.github.randomcodespace.iq.detector.csharp;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
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

@DetectorInfo(
    name = "csharp_structures",
    category = "structures",
    description = "Detects C# classes, interfaces, enums, records, and controller endpoints",
    parser = ParserType.REGEX,
    languages = {"csharp"},
    nodeKinds = {NodeKind.ABSTRACT_CLASS, NodeKind.CLASS, NodeKind.ENDPOINT, NodeKind.ENUM, NodeKind.INTERFACE, NodeKind.MODULE},
    edgeKinds = {EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.IMPORTS},
    properties = {"base_class", "http_method", "path"}
)
@Component
public class CSharpStructuresDetector extends AbstractAntlrDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public|internal|private|protected)?\\s*(?:abstract|static|sealed|partial)?\\s*class\\s+(\\w+)(?:\\s*<[^>]+>)?(?:\\s*:\\s*([^{]+))?");
    private static final Pattern INTERFACE_RE = Pattern.compile("(?:public|internal)?\\s*interface\\s+(\\w+)(?:\\s*<[^>]+>)?(?:\\s*:\\s*([^{]+))?");
    private static final Pattern ENUM_RE = Pattern.compile("(?:public|internal)?\\s*enum\\s+(\\w+)");
    private static final Pattern NAMESPACE_RE = Pattern.compile("namespace\\s+([\\w.]+)");
    private static final Pattern USING_RE = Pattern.compile("^\\s*using\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern HTTP_ATTR_RE = Pattern.compile("\\[(Http(?:Get|Post|Put|Delete|Patch))\\s*(?:\\(\"([^\"]*)\"\\))?\\]");
    private static final Pattern ROUTE_RE = Pattern.compile("\\[Route\\(\"([^\"]*)\"\\)\\]");
    private static final Pattern API_CONTROLLER_RE = Pattern.compile("\\[ApiController\\]");
    private static final Pattern METHOD_RE = Pattern.compile("(?:public|protected|private|internal)\\s+(?:static\\s+|virtual\\s+|override\\s+|async\\s+|abstract\\s+)*(?:[\\w<>\\[\\]?,\\s]+)\\s+(\\w+)\\s*\\(");

    @Override
    public String getName() { return "csharp_structures"; }

    @Override
    public Set<String> getSupportedLanguages() { return Set.of("csharp"); }
    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // Skip ANTLR parsing — regex is the primary detection method for this detector
        // ANTLR infrastructure is in place for future enhancement
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String filePath = ctx.filePath();
        String[] lines = text.split("\n", -1);

        // Namespace
        String namespace = null;
        Matcher nsM = NAMESPACE_RE.matcher(text);
        if (nsM.find()) {
            namespace = nsM.group(1);
            CodeNode node = new CodeNode();
            node.setId(filePath + ":namespace:" + namespace);
            node.setKind(NodeKind.MODULE);
            node.setLabel(namespace);
            node.setFqn(namespace);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, nsM.start()));
            nodes.add(node);
        }

        // Using statements
        Matcher um = USING_RE.matcher(text);
        while (um.find()) {
            CodeEdge edge = new CodeEdge();
            edge.setId(filePath + ":imports:" + um.group(1));
            edge.setKind(EdgeKind.IMPORTS);
            edge.setSourceId(filePath);
            edge.setTarget(new CodeNode(um.group(1), NodeKind.MODULE, um.group(1)));
            edges.add(edge);
        }

        // Class-level route
        String classRoute = null;

        // Classes
        Matcher cm = CLASS_RE.matcher(text);
        while (cm.find()) {
            String className = cm.group(1);
            String baseStr = cm.group(2);
            int lineNum = findLineNumber(text, cm.start());
            String matchText = text.substring(Math.max(0, cm.start() - 60), Math.min(text.length(), cm.start() + cm.group(0).length()));
            boolean isAbstract = matchText.contains("abstract");
            NodeKind kind = isAbstract ? NodeKind.ABSTRACT_CLASS : NodeKind.CLASS;
            String fqn = namespace != null ? namespace + "." + className : className;
            String nodeId = filePath + ":" + className;

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(kind);
            node.setLabel(className);
            node.setFqn(fqn);
            node.setFilePath(filePath);
            node.setLineStart(lineNum);
            if (isAbstract) node.getProperties().put("is_abstract", true);

            String[] parsed = parseBaseTypes(baseStr);
            String baseClass = parsed[0];
            List<String> ifaceList = new ArrayList<>();
            for (int i = 1; i < parsed.length; i++) {
                if (parsed[i] != null) ifaceList.add(parsed[i]);
            }

            if (baseClass != null) {
                node.getProperties().put("base_class", baseClass);
                CodeEdge edge = new CodeEdge();
                edge.setId(nodeId + ":extends:" + baseClass);
                edge.setKind(EdgeKind.EXTENDS);
                edge.setSourceId(nodeId);
                edge.setTarget(new CodeNode("*:" + baseClass, NodeKind.CLASS, baseClass));
                edges.add(edge);
            }
            if (!ifaceList.isEmpty()) {
                node.getProperties().put("interfaces", ifaceList);
                for (String iface : ifaceList) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(nodeId + ":implements:" + iface);
                    edge.setKind(EdgeKind.IMPLEMENTS);
                    edge.setSourceId(nodeId);
                    edge.setTarget(new CodeNode("*:" + iface, NodeKind.INTERFACE, iface));
                    edges.add(edge);
                }
            }
            nodes.add(node);

            // Check for [Route] attribute above class
            int classLineIdx = lineNum - 1;
            for (int j = Math.max(0, classLineIdx - 5); j < classLineIdx && j < lines.length; j++) {
                Matcher rm = ROUTE_RE.matcher(lines[j]);
                if (rm.find()) {
                    classRoute = rm.group(1);
                    String controllerName = className;
                    if (controllerName.endsWith("Controller")) {
                        controllerName = controllerName.substring(0, controllerName.length() - "Controller".length());
                    }
                    classRoute = classRoute.replace("[controller]", controllerName);
                    break;
                }
            }
        }

        // Interfaces
        Matcher im = INTERFACE_RE.matcher(text);
        while (im.find()) {
            String ifaceName = im.group(1);
            String fqn = namespace != null ? namespace + "." + ifaceName : ifaceName;
            CodeNode node = new CodeNode();
            node.setId(filePath + ":" + ifaceName);
            node.setKind(NodeKind.INTERFACE);
            node.setLabel(ifaceName);
            node.setFqn(fqn);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, im.start()));
            nodes.add(node);
        }

        // Enums
        Matcher em = ENUM_RE.matcher(text);
        while (em.find()) {
            String enumName = em.group(1);
            String fqn = namespace != null ? namespace + "." + enumName : enumName;
            CodeNode node = new CodeNode();
            node.setId(filePath + ":" + enumName);
            node.setKind(NodeKind.ENUM);
            node.setLabel(enumName);
            node.setFqn(fqn);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, em.start()));
            nodes.add(node);
        }

        // HTTP endpoints
        Set<String> skipWords = Set.of("if", "for", "while", "switch", "catch", "using", "return", "new", "class");
        final String finalClassRoute = classRoute;
        final String finalNamespace = namespace;
        for (int i = 0; i < lines.length; i++) {
            Matcher mm = METHOD_RE.matcher(lines[i]);
            if (!mm.find()) continue;
            String methodName = mm.group(1);
            if (skipWords.contains(methodName)) continue;

            String httpMethodStr = null;
            String httpPath = null;
            for (int j = Math.max(0, i - 5); j < i; j++) {
                Matcher hm = HTTP_ATTR_RE.matcher(lines[j]);
                if (hm.find()) {
                    httpMethodStr = hm.group(1).replace("Http", "").toUpperCase();
                    httpPath = hm.group(2);
                    break;
                }
            }

            if (httpMethodStr != null) {
                String path = httpPath != null ? httpPath : "";
                String fullPath;
                if (finalClassRoute != null) {
                    fullPath = "/" + finalClassRoute.replaceAll("(^/+)|(/+$)", "");
                    if (!path.isEmpty()) fullPath = fullPath + "/" + path.replaceAll("^/+", "");
                } else {
                    fullPath = !path.isEmpty() ? "/" + path.replaceAll("^/+", "") : "/";
                }

                CodeNode node = new CodeNode();
                node.setId("endpoint:" + ctx.moduleName() + ":" + methodName + ":" + httpMethodStr + ":" + fullPath);
                node.setKind(NodeKind.ENDPOINT);
                node.setLabel(httpMethodStr + " " + fullPath);
                node.setFqn(finalNamespace != null ? finalNamespace + "." + methodName : methodName);
                node.setFilePath(filePath);
                node.setLineStart(i + 1);
                node.getProperties().put("http_method", httpMethodStr);
                node.getProperties().put("path", fullPath);
                nodes.add(node);
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private static String[] parseBaseTypes(String baseStr) {
        if (baseStr == null || baseStr.isBlank()) return new String[]{null};
        String[] parts = baseStr.split(",");
        List<String> result = new ArrayList<>();
        String baseClass = null;
        for (String part : parts) {
            String clean = part.replaceAll("<[^>]*>", "").trim();
            if (clean.isEmpty()) continue;
            if (clean.length() >= 2 && clean.charAt(0) == 'I' && Character.isUpperCase(clean.charAt(1))) {
                result.add(clean);
            } else if (baseClass == null) {
                baseClass = clean;
            } else {
                result.add(clean);
            }
        }
        String[] out = new String[1 + result.size()];
        out[0] = baseClass;
        for (int i = 0; i < result.size(); i++) out[i + 1] = result.get(i);
        return out;
    }
}
