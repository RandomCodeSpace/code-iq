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

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "mongoose_orm",
    category = "database",
    description = "Detects Mongoose schemas, models, and database connections",
    parser = ParserType.REGEX,
    languages = {"typescript", "javascript"},
    nodeKinds = {NodeKind.DATABASE_CONNECTION, NodeKind.ENTITY, NodeKind.EVENT},
    edgeKinds = {EdgeKind.QUERIES},
    properties = {"framework", "operation"}
)
@Component
public class MongooseORMDetector extends AbstractAntlrDetector {

    private static final Pattern MODEL_RE = Pattern.compile(
            "mongoose\\.model\\s*\\(\\s*['\"](\\w+)['\"]"
    );
    private static final Pattern SCHEMA_RE = Pattern.compile(
            "(?:const|let|var)\\s+(\\w+)\\s*=\\s*new\\s+(?:mongoose\\.)?Schema\\s*\\("
    );
    private static final Pattern CONNECT_RE = Pattern.compile(
            "mongoose\\.connect\\s*\\("
    );
    private static final Pattern QUERY_RE = Pattern.compile(
            "(\\w+)\\.(find|findOne|findById|findOneAndUpdate|findOneAndDelete" +
            "|create|insertMany|updateOne|updateMany|deleteOne|deleteMany" +
            "|countDocuments|aggregate)\\s*\\("
    );
    private static final Pattern VIRTUAL_RE = Pattern.compile(
            "(\\w+)\\.virtual\\s*\\(\\s*['\"](\\w+)['\"]"
    );
    private static final Pattern HOOK_RE = Pattern.compile(
            "(\\w+)\\.(pre|post)\\s*\\(\\s*['\"](\\w+)['\"]"
    );

    @Override
    public String getName() {
        return "mongoose_orm";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript", "javascript");
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

        Map<String, String> seenModels = new LinkedHashMap<>();
        Set<String> schemaVars = new LinkedHashSet<>();

        // mongoose.connect -> DATABASE_CONNECTION
        Matcher matcher = CONNECT_RE.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            CodeNode node = new CodeNode();
            node.setId("mongoose:" + filePath + ":connection:" + line);
            node.setKind(NodeKind.DATABASE_CONNECTION);
            node.setLabel("mongoose.connect");
            node.setFqn(filePath + "::mongoose.connect");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("framework", "mongoose");
            nodes.add(node);
        }

        // new Schema({ ... }) -> ENTITY
        matcher = SCHEMA_RE.matcher(text);
        while (matcher.find()) {
            String varName = matcher.group(1);
            schemaVars.add(varName);
            int line = findLineNumber(text, matcher.start());
            CodeNode node = new CodeNode();
            node.setId("mongoose:" + filePath + ":schema:" + varName);
            node.setKind(NodeKind.ENTITY);
            node.setLabel(varName);
            node.setFqn(filePath + "::" + varName);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("framework", "mongoose");
            node.getProperties().put("definition", "schema");
            nodes.add(node);
        }

        // mongoose.model('Name', schema) -> ENTITY
        matcher = MODEL_RE.matcher(text);
        while (matcher.find()) {
            String modelName = matcher.group(1);
            int line = findLineNumber(text, matcher.start());
            String modelId = "mongoose:" + filePath + ":model:" + modelName;
            seenModels.put(modelName, modelId);
            CodeNode node = new CodeNode();
            node.setId(modelId);
            node.setKind(NodeKind.ENTITY);
            node.setLabel(modelName);
            node.setFqn(filePath + "::" + modelName);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("framework", "mongoose");
            node.getProperties().put("definition", "model");
            nodes.add(node);
        }

        // schema.virtual('name')
        List<String> virtuals = new ArrayList<>();
        matcher = VIRTUAL_RE.matcher(text);
        while (matcher.find()) {
            String varName = matcher.group(1);
            String virtualName = matcher.group(2);
            if (schemaVars.contains(varName)) {
                virtuals.add(virtualName);
            }
        }
        if (!virtuals.isEmpty()) {
            for (CodeNode node : nodes) {
                if ("schema".equals(node.getProperties().get("definition"))) {
                    node.getProperties().put("virtuals", virtuals);
                }
            }
        }

        // schema.pre/post hooks -> EVENT nodes
        matcher = HOOK_RE.matcher(text);
        while (matcher.find()) {
            String varName = matcher.group(1);
            String hookType = matcher.group(2);
            String eventName = matcher.group(3);
            if (schemaVars.contains(varName)) {
                int line = findLineNumber(text, matcher.start());
                String eventId = "mongoose:" + filePath + ":hook:" + hookType + ":" + eventName + ":" + line;
                CodeNode node = new CodeNode();
                node.setId(eventId);
                node.setKind(NodeKind.EVENT);
                node.setLabel(hookType + ":" + eventName);
                node.setFqn(filePath + "::" + hookType + ":" + eventName);
                node.setModule(moduleName);
                node.setFilePath(filePath);
                node.setLineStart(line);
                node.getProperties().put("framework", "mongoose");
                node.getProperties().put("hook_type", hookType);
                node.getProperties().put("event", eventName);
                nodes.add(node);
            }
        }

        // query operations -> QUERIES edges
        matcher = QUERY_RE.matcher(text);
        while (matcher.find()) {
            String modelName = matcher.group(1);
            String operation = matcher.group(2);
            int line = findLineNumber(text, matcher.start());
            String targetId = seenModels.getOrDefault(modelName,
                    "mongoose:" + filePath + ":model:" + modelName);
            CodeEdge edge = new CodeEdge();
            edge.setId(filePath + "->queries->" + targetId + ":" + line);
            edge.setKind(EdgeKind.QUERIES);
            edge.setSourceId(filePath);
            edge.getProperties().put("operation", operation);
            edge.getProperties().put("line", line);
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }
}
