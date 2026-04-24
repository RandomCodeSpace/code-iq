package io.github.randomcodespace.iq.detector.go;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
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
    name = "go_structures",
    category = "structures",
    description = "Detects Go structs, interfaces, functions, and imports",
    parser = ParserType.REGEX,
    languages = {"go"},
    nodeKinds = {NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.METHOD, NodeKind.MODULE},
    edgeKinds = {EdgeKind.DEFINES, EdgeKind.IMPORTS}
)
@Component
public class GoStructuresDetector extends AbstractAntlrDetector {
    private static final String PROP_EXPORTED = "exported";


    private static final Pattern STRUCT_RE = Pattern.compile("type\\s+(\\w+)\\s+struct\\s*\\{");
    private static final Pattern INTERFACE_RE = Pattern.compile("type\\s+(\\w+)\\s+interface\\s*\\{");
    private static final Pattern METHOD_RE = Pattern.compile("func\\s+\\(\\s*\\w+\\s+\\*?(\\w+)\\s*\\)\\s+(\\w+)\\s*\\(");
    private static final Pattern FUNC_RE = Pattern.compile("^func\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern PACKAGE_RE = Pattern.compile("^package\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern IMPORT_SINGLE_RE = Pattern.compile("^import\\s+\"([^\"]+)\"", Pattern.MULTILINE);
    private static final Pattern IMPORT_BLOCK_RE = Pattern.compile("import\\s*\\((.*?)\\)", Pattern.DOTALL);
    private static final Pattern IMPORT_PATH_RE = Pattern.compile("\"([^\"]+)\"");

    @Override
    public String getName() {
        return "go_structures";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("go");
    }
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

        // Package
        Matcher pkgM = PACKAGE_RE.matcher(text);
        String pkgName = null;
        if (pkgM.find()) {
            pkgName = pkgM.group(1);
            CodeNode node = new CodeNode();
            node.setId(filePath + ":package:" + pkgName);
            node.setKind(NodeKind.MODULE);
            node.setLabel(pkgName);
            node.setFqn(pkgName);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, pkgM.start()));
            node.getProperties().put("package", pkgName);
            nodes.add(node);
        }

        // Single imports
        Matcher im = IMPORT_SINGLE_RE.matcher(text);
        while (im.find()) {
            CodeEdge edge = new CodeEdge();
            edge.setId(filePath + ":imports:" + im.group(1));
            edge.setKind(EdgeKind.IMPORTS);
            edge.setSourceId(filePath);
            edge.setTarget(new CodeNode(im.group(1), NodeKind.MODULE, im.group(1)));
            edges.add(edge);
        }

        // Block imports
        Matcher bm = IMPORT_BLOCK_RE.matcher(text);
        while (bm.find()) {
            Matcher pm = IMPORT_PATH_RE.matcher(bm.group(1));
            while (pm.find()) {
                CodeEdge edge = new CodeEdge();
                edge.setId(filePath + ":imports:" + pm.group(1));
                edge.setKind(EdgeKind.IMPORTS);
                edge.setSourceId(filePath);
                edge.setTarget(new CodeNode(pm.group(1), NodeKind.MODULE, pm.group(1)));
                edges.add(edge);
            }
        }

        // Structs
        Matcher sm = STRUCT_RE.matcher(text);
        while (sm.find()) {
            String name = sm.group(1);
            boolean exported = Character.isUpperCase(name.charAt(0));
            CodeNode node = new CodeNode();
            node.setId(filePath + ":" + name);
            node.setKind(NodeKind.CLASS);
            node.setLabel(name);
            node.setFqn(pkgName != null ? pkgName + "." + name : name);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, sm.start()));
            node.getProperties().put(PROP_EXPORTED, exported);
            node.getProperties().put("type", "struct");
            nodes.add(node);
        }

        // Interfaces
        Matcher ifm = INTERFACE_RE.matcher(text);
        while (ifm.find()) {
            String name = ifm.group(1);
            boolean exported = Character.isUpperCase(name.charAt(0));
            CodeNode node = new CodeNode();
            node.setId(filePath + ":" + name);
            node.setKind(NodeKind.INTERFACE);
            node.setLabel(name);
            node.setFqn(pkgName != null ? pkgName + "." + name : name);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, ifm.start()));
            node.getProperties().put(PROP_EXPORTED, exported);
            nodes.add(node);
        }

        // Methods
        Matcher mm = METHOD_RE.matcher(text);
        while (mm.find()) {
            String receiver = mm.group(1);
            String methodName = mm.group(2);
            boolean exported = Character.isUpperCase(methodName.charAt(0));
            CodeNode node = new CodeNode();
            node.setId(filePath + ":" + receiver + ":" + methodName);
            node.setKind(NodeKind.METHOD);
            node.setLabel(receiver + "." + methodName);
            node.setFqn(pkgName != null ? pkgName + "." + receiver + "." + methodName : receiver + "." + methodName);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, mm.start()));
            node.getProperties().put(PROP_EXPORTED, exported);
            node.getProperties().put("receiver_type", receiver);
            nodes.add(node);

            CodeEdge edge = new CodeEdge();
            edge.setId(filePath + ":" + receiver + ":defines:" + methodName);
            edge.setKind(EdgeKind.DEFINES);
            edge.setSourceId(filePath + ":" + receiver);
            edge.setTarget(new CodeNode(filePath + ":" + receiver + ":" + methodName, NodeKind.METHOD, methodName));
            edges.add(edge);
        }

        // Package-level functions
        Matcher fm = FUNC_RE.matcher(text);
        // Need to re-scan method positions
        Set<Integer> methodStarts = new HashSet<>();
        Matcher mm2 = METHOD_RE.matcher(text);
        while (mm2.find()) methodStarts.add(mm2.start());

        while (fm.find()) {
            if (methodStarts.contains(fm.start())) continue;
            String funcName = fm.group(1);
            boolean exported = Character.isUpperCase(funcName.charAt(0));
            CodeNode node = new CodeNode();
            node.setId(filePath + ":" + funcName);
            node.setKind(NodeKind.METHOD);
            node.setLabel(funcName);
            node.setFqn(pkgName != null ? pkgName + "." + funcName : funcName);
            node.setFilePath(filePath);
            node.setLineStart(findLineNumber(text, fm.start()));
            node.getProperties().put(PROP_EXPORTED, exported);
            node.getProperties().put("type", "function");
            nodes.add(node);
        }

        return DetectorResult.of(nodes, edges);
    }
}
