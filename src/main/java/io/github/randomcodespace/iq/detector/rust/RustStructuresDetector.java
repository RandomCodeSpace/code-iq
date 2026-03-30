package io.github.randomcodespace.iq.detector.rust;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
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

@DetectorInfo(
    name = "rust_structures",
    category = "structures",
    description = "Detects Rust structs, traits, impls, functions, enums, and use statements",
    parser = ParserType.REGEX,
    languages = {"rust"},
    nodeKinds = {NodeKind.CLASS, NodeKind.ENUM, NodeKind.INTERFACE, NodeKind.METHOD, NodeKind.MODULE},
    edgeKinds = {EdgeKind.DEFINES, EdgeKind.IMPLEMENTS, EdgeKind.IMPORTS}
)
@Component
public class RustStructuresDetector extends AbstractAntlrDetector {

    private static final Pattern USE_RE = Pattern.compile("^\\s*use\\s+([\\w:]+)", Pattern.MULTILINE);
    private static final Pattern STRUCT_RE = Pattern.compile("^\\s*(?:pub\\s+)?struct\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern TRAIT_RE = Pattern.compile("^\\s*(?:pub\\s+)?trait\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern IMPL_RE = Pattern.compile("^\\s*impl(?:<[^>]*>)?\\s+(\\w+)(?:\\s+for\\s+(\\w+))?\\s*\\{", Pattern.MULTILINE);
    private static final Pattern FN_RE = Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?fn\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern MOD_RE = Pattern.compile("^\\s*(?:pub\\s+)?mod\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern ENUM_RE = Pattern.compile("^\\s*(?:pub\\s+)?enum\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern MACRO_RE = Pattern.compile("^\\s*macro_rules!\\s+(\\w+)", Pattern.MULTILINE);

    @Override
    public String getName() { return "rust_structures"; }

    @Override
    public Set<String> getSupportedLanguages() { return Set.of("rust"); }
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

        Matcher m = USE_RE.matcher(text);
        while (m.find()) {
            String target = m.group(1);
            CodeEdge e = new CodeEdge(); e.setId(fp + ":imports:" + target);
            e.setKind(EdgeKind.IMPORTS); e.setSourceId(fp);
            e.setTarget(new CodeNode(target, NodeKind.MODULE, target));
            edges.add(e);
        }

        m = MOD_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            CodeNode n = new CodeNode(); n.setId(fp + ":mod:" + name);
            n.setKind(NodeKind.MODULE); n.setLabel(name); n.setFqn(name);
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            nodes.add(n);
        }

        m = STRUCT_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            CodeNode n = new CodeNode(); n.setId(fp + ":" + name);
            n.setKind(NodeKind.CLASS); n.setLabel(name); n.setFqn(name);
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            n.getProperties().put("type", "struct"); nodes.add(n);
        }

        m = TRAIT_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            CodeNode n = new CodeNode(); n.setId(fp + ":" + name);
            n.setKind(NodeKind.INTERFACE); n.setLabel(name); n.setFqn(name);
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            n.getProperties().put("type", "trait"); nodes.add(n);
        }

        m = ENUM_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            CodeNode n = new CodeNode(); n.setId(fp + ":" + name);
            n.setKind(NodeKind.ENUM); n.setLabel(name); n.setFqn(name);
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            nodes.add(n);
        }

        m = IMPL_RE.matcher(text);
        while (m.find()) {
            String first = m.group(1);
            String second = m.group(2);
            if (second != null) {
                CodeEdge e = new CodeEdge(); e.setId(fp + ":" + second + ":implements:" + first);
                e.setKind(EdgeKind.IMPLEMENTS); e.setSourceId(fp + ":" + second);
                e.setTarget(new CodeNode(fp + ":" + first, NodeKind.INTERFACE, first));
                edges.add(e);
            } else {
                CodeEdge e = new CodeEdge(); e.setId(fp + ":" + first + ":defines:" + first);
                e.setKind(EdgeKind.DEFINES); e.setSourceId(fp + ":" + first);
                e.setTarget(new CodeNode(fp + ":" + first, NodeKind.CLASS, first));
                edges.add(e);
            }
        }

        m = FN_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            CodeNode n = new CodeNode(); n.setId(fp + ":" + name);
            n.setKind(NodeKind.METHOD); n.setLabel(name); n.setFqn(name);
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            n.getProperties().put("type", "function"); nodes.add(n);
        }

        m = MACRO_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            CodeNode n = new CodeNode(); n.setId(fp + ":macro:" + name);
            n.setKind(NodeKind.METHOD); n.setLabel(name + "!"); n.setFqn(name + "!");
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            n.getProperties().put("type", "macro"); nodes.add(n);
        }

        return DetectorResult.of(nodes, edges);
    }
}
