package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Batch script structures: labels, CALL commands, and SET variables.
 */
@Component
public class BatchStructureDetector extends AbstractRegexDetector {

    private static final Pattern LABEL_RE = Pattern.compile("^:(\\w+)");
    private static final Pattern CALL_RE = Pattern.compile("CALL\\s+:?(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SET_RE = Pattern.compile("SET\\s+(\\w+)=", Pattern.CASE_INSENSITIVE);

    @Override
    public String getName() {
        return "batch_structure";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("batch");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String content = ctx.content();
        if (content == null || content.isEmpty()) return DetectorResult.empty();

        String filepath = ctx.filePath();
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String moduleId = "bat:" + filepath;

        // MODULE node for the script
        CodeNode moduleNode = new CodeNode(moduleId, NodeKind.MODULE, filepath);
        moduleNode.setFqn(filepath);
        moduleNode.setModule(ctx.moduleName());
        moduleNode.setFilePath(filepath);
        moduleNode.setLineStart(1);
        nodes.add(moduleNode);

        for (IndexedLine il : iterLines(content)) {
            String line = il.text();
            int lineNum = il.lineNumber();
            String stripped = line.strip();

            if (stripped.isEmpty()) continue;
            String upper = stripped.toUpperCase();
            if (upper.startsWith("@ECHO OFF")) continue;
            if (upper.startsWith("REM ") || upper.equals("REM")) continue;
            if (stripped.startsWith("::")) continue;

            // Labels
            Matcher m = LABEL_RE.matcher(stripped);
            if (m.find()) {
                String labelName = m.group(1);
                String labelId = "bat:" + filepath + ":label:" + labelName;
                CodeNode labelNode = new CodeNode(labelId, NodeKind.METHOD, ":" + labelName);
                labelNode.setFqn(filepath + ":" + labelName);
                labelNode.setModule(ctx.moduleName());
                labelNode.setFilePath(filepath);
                labelNode.setLineStart(lineNum);
                nodes.add(labelNode);

                CodeEdge edge = new CodeEdge();
                edge.setId(moduleId + "->" + labelId);
                edge.setKind(EdgeKind.CONTAINS);
                edge.setSourceId(moduleId);
                edge.setTarget(new CodeNode(labelId, null, null));
                edges.add(edge);
                continue;
            }

            // CALL commands
            m = CALL_RE.matcher(stripped);
            if (m.find()) {
                String callTarget = m.group(1);
                String targetId;
                if (callTarget.startsWith(":")) {
                    targetId = "bat:" + filepath + ":label:" + callTarget.substring(1);
                } else if (callTarget.contains(".")) {
                    targetId = callTarget;
                } else {
                    targetId = "bat:" + filepath + ":label:" + callTarget;
                }

                CodeEdge edge = new CodeEdge();
                edge.setId(moduleId + "->" + targetId);
                edge.setKind(EdgeKind.CALLS);
                edge.setSourceId(moduleId);
                edge.setTarget(new CodeNode(targetId, null, null));
                edges.add(edge);
            }

            // SET variables
            m = SET_RE.matcher(stripped);
            if (m.find()) {
                String varName = m.group(1);
                CodeNode varNode = new CodeNode("bat:" + filepath + ":set:" + varName,
                        NodeKind.CONFIG_DEFINITION, "SET " + varName);
                varNode.setFqn(filepath + ":" + varName);
                varNode.setModule(ctx.moduleName());
                varNode.setFilePath(filepath);
                varNode.setLineStart(lineNum);
                varNode.setProperties(Map.of("variable", varName));
                nodes.add(varNode);
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
