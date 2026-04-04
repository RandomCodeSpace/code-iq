package io.github.randomcodespace.iq.detector.kotlin;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.StructuresDetectorHelper;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

// Kotlin Compiler API (kotlin-compiler-embeddable) evaluated 2026-03-28 but regex/ANTLR
// approach provides sufficient detection quality for Kotlin structures. The compiler JAR
// adds 50-70MB for only 2 Kotlin detectors — not justified. Revisit if complex semantic
// analysis (type resolution, coroutine flow tracking) is needed in the future.
@DetectorInfo(
    name = "kotlin_structures",
    category = "structures",
    description = "Detects Kotlin classes, interfaces, objects, functions, and imports",
    parser = ParserType.REGEX,
    languages = {"kotlin"},
    nodeKinds = {NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.METHOD, NodeKind.MODULE},
    edgeKinds = {EdgeKind.EXTENDS, EdgeKind.IMPORTS}
)
@Component
public class KotlinStructuresDetector extends AbstractAntlrDetector {

    private static final Pattern IMPORT_RE = Pattern.compile("^\\s*import\\s+([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern CLASS_RE = Pattern.compile("^\\s*(?:(?:data|open|abstract|sealed|enum|annotation|value|inline)\\s+)*class\\s+(\\w+)(?:\\s*(?:\\(.*?\\))?\\s*:\\s*([\\w\\s,.<>]+))?", Pattern.MULTILINE);
    private static final Pattern INTERFACE_RE = Pattern.compile("^\\s*interface\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern FUN_RE = Pattern.compile("^\\s*(?:(?:override|inline|private|protected|internal|public)\\s+)*(?:fun|suspend\\s+fun)\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern OBJECT_RE = Pattern.compile("^\\s*object\\s+(\\w+)", Pattern.MULTILINE);

    @Override
    public String getName() { return "kotlin_structures"; }

    @Override
    public Set<String> getSupportedLanguages() { return Set.of("kotlin"); }
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
        String fp = ctx.filePath();

        Matcher m = IMPORT_RE.matcher(text);
        while (m.find()) {
            StructuresDetectorHelper.addImportEdge(fp, m.group(1), edges);
        }

        m = CLASS_RE.matcher(text);
        while (m.find()) {
            String className = m.group(1);
            String supertypesStr = m.group(2);
            String nodeId = fp + ":" + className;
            nodes.add(StructuresDetectorHelper.createStructureNode(fp, className, NodeKind.CLASS, findLineNumber(text, m.start())));
            if (supertypesStr != null) {
                for (String st : supertypesStr.split(",")) {
                    st = st.trim().split("\\(")[0].split("<")[0].trim();
                    if (!st.isEmpty()) {
                        StructuresDetectorHelper.addExtendsEdge(nodeId, st, NodeKind.CLASS, edges);
                    }
                }
            }
        }

        m = INTERFACE_RE.matcher(text);
        while (m.find()) {
            nodes.add(StructuresDetectorHelper.createStructureNode(fp, m.group(1), NodeKind.INTERFACE, findLineNumber(text, m.start())));
        }

        m = OBJECT_RE.matcher(text);
        while (m.find()) {
            CodeNode n = StructuresDetectorHelper.createStructureNode(fp, m.group(1), NodeKind.CLASS, findLineNumber(text, m.start()));
            n.getProperties().put("type", "object");
            nodes.add(n);
        }

        m = FUN_RE.matcher(text);
        while (m.find()) {
            nodes.add(StructuresDetectorHelper.createStructureNode(fp, m.group(1), NodeKind.METHOD, findLineNumber(text, m.start())));
        }

        return DetectorResult.of(nodes, edges);
    }
}
