package io.github.randomcodespace.iq.detector.typescript;

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
    name = "typescript.typeorm_entities",
    category = "entities",
    description = "Detects TypeORM entity definitions and column mappings",
    parser = ParserType.REGEX,
    languages = {"typescript"},
    nodeKinds = {NodeKind.ENTITY},
    edgeKinds = {EdgeKind.MAPS_TO},
    properties = {"columns", "framework", "table_name"}
)
@Component
public class TypeORMEntityDetector extends AbstractAntlrDetector {

    private static final Pattern ENTITY_PATTERN = Pattern.compile(
            "@Entity\\(\\s*['\"`]?(\\w*)['\"`]?\\s*\\)\\s*\\n\\s*(?:export\\s+)?class\\s+(\\w+)"
    );

    private static final Pattern COLUMN_PATTERN = Pattern.compile(
            "@Column\\([^)]*\\)\\s*\\n?\\s*(\\w+)\\s*[!?]?\\s*:\\s*(\\w+)"
    );

    private static final Pattern RELATION_PATTERN = Pattern.compile(
            "@(ManyToOne|OneToMany|ManyToMany|OneToOne)\\(\\s*\\(\\)\\s*=>\\s*(\\w+)"
    );

    @Override
    public String getName() {
        return "typescript.typeorm_entities";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // Skip ANTLR parsing — regex is the primary detection method for this detector
        // ANTLR infrastructure is in place for future enhancement
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        Matcher matcher = ENTITY_PATTERN.matcher(text);
        while (matcher.find()) {
            String tableName = matcher.group(1);
            String className = matcher.group(2);
            if (tableName == null || tableName.isEmpty()) {
                tableName = className.toLowerCase() + "s";
            }
            int line = findLineNumber(text, matcher.start());

            // Find class body by brace matching
            int classStart = matcher.end();
            int braceCount = 0;
            int classEnd = text.length();
            for (int i = classStart; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '{') braceCount++;
                else if (ch == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        classEnd = i;
                        break;
                    }
                }
            }
            String classBody = text.substring(classStart, classEnd);

            // Extract columns
            List<String> columns = new ArrayList<>();
            Matcher colMatcher = COLUMN_PATTERN.matcher(classBody);
            while (colMatcher.find()) {
                columns.add(colMatcher.group(1));
            }

            String nodeId = "entity:" + (moduleName != null ? moduleName : "") + ":" + className;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.ENTITY);
            node.setLabel(className);
            node.setFqn(filePath + "::" + className);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getAnnotations().add("@Entity");
            node.getProperties().put("table_name", tableName);
            node.getProperties().put("columns", columns);
            node.getProperties().put("framework", "typeorm");
            nodes.add(node);

            // Detect relationships
            Matcher relMatcher = RELATION_PATTERN.matcher(classBody);
            while (relMatcher.find()) {
                String relType = relMatcher.group(1);
                String targetEntity = relMatcher.group(2);
                String targetId = "entity:" + (moduleName != null ? moduleName : "") + ":" + targetEntity;
                CodeEdge edge = new CodeEdge();
                edge.setId(nodeId + "->" + relType + "->" + targetId);
                edge.setKind(EdgeKind.MAPS_TO);
                edge.setSourceId(nodeId);
                edges.add(edge);
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
