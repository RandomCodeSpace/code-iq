package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.grammar.python.Python3Parser;
import io.github.randomcodespace.iq.grammar.python.Python3ParserBaseListener;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "python.celery_tasks",
    category = "messaging",
    description = "Detects Celery task definitions and queue bindings",
    parser = ParserType.ANTLR,
    languages = {"python"},
    nodeKinds = {NodeKind.METHOD, NodeKind.QUEUE},
    edgeKinds = {EdgeKind.CONSUMES, EdgeKind.PRODUCES},
    properties = {"broker", "task_name"}
)
@Component
public class CeleryTaskDetector extends AbstractPythonAntlrDetector {

    // --- Regex patterns ---
    private static final Pattern TASK_DECORATOR = Pattern.compile(
            "@(?:\\w+\\.)?(?:task|shared_task)\\(?"
            + "(?:.*?name\\s*=\\s*['\"]([^'\"]+)['\"])?"
            + "[^)]*\\)?\\s*\\n\\s*def\\s+(\\w+)",
            Pattern.DOTALL
    );
    private static final Pattern TASK_CALL = Pattern.compile(
            "(\\w+)\\.(delay|apply_async|s|si|signature)\\("
    );
    private static final Pattern NAME_KWARG_RE = Pattern.compile(
            "name\\s*=\\s*['\"]([^'\"]+)['\"]"
    );

    private static final Set<String> TASK_DECORATORS = Set.of("task", "shared_task");

    @Override
    public String getName() {
        return "python.celery_tasks";
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();
        String text = ctx.content();

        // Walk for decorated functions (task definitions)
        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterDecorated(Python3Parser.DecoratedContext decorated) {
                if (decorated.decorators() == null) return;

                String funcName = null;
                if (decorated.funcdef() != null && decorated.funcdef().name() != null) {
                    funcName = decorated.funcdef().name().getText();
                } else if (decorated.async_funcdef() != null
                        && decorated.async_funcdef().funcdef() != null
                        && decorated.async_funcdef().funcdef().name() != null) {
                    funcName = decorated.async_funcdef().funcdef().name().getText();
                }
                if (funcName == null) return;

                for (var dec : decorated.decorators().decorator()) {
                    if (dec.dotted_name() == null) continue;
                    var names = dec.dotted_name().name();
                    String lastPart = names.get(names.size() - 1).getText();
                    if (!TASK_DECORATORS.contains(lastPart)) continue;

                    // Extract task name from name=... kwarg
                    String taskName = null;
                    if (dec.arglist() != null) {
                        String argText = dec.arglist().getText();
                        Matcher nm = NAME_KWARG_RE.matcher(argText);
                        if (nm.find()) {
                            taskName = nm.group(1);
                        }
                    }
                    if (taskName == null) {
                        taskName = funcName;
                    }

                    int line = lineOf(dec);

                    String queueId = "queue:" + (moduleName != null ? moduleName : "") + ":celery:" + taskName;
                    nodes.add(createQueueNode(queueId, taskName, funcName, moduleName, filePath, line));

                    String methodId = "method:" + filePath + "::" + funcName;
                    nodes.add(createMethodNode(methodId, funcName, moduleName, filePath, line));

                    edges.add(createConsumesEdge(methodId, queueId));
                }
            }

            @Override
            public void enterAtom_expr(Python3Parser.Atom_exprContext atomExpr) {
                String exprText = atomExpr.getText();
                Matcher callMatcher = TASK_CALL.matcher(exprText);
                if (callMatcher.find()) {
                    String taskRef = callMatcher.group(1);
                    int line = lineOf(atomExpr);

                    String queueId = "queue:" + (moduleName != null ? moduleName : "") + ":celery:" + taskRef;
                    String callerId = "method:" + filePath + "::caller_l" + line;

                    edges.add(createProducesEdge(callerId, queueId));
                }
            }
        }, tree);

        return DetectorResult.of(nodes, edges);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        Matcher taskMatcher = TASK_DECORATOR.matcher(text);
        while (taskMatcher.find()) {
            String taskName = taskMatcher.group(1) != null ? taskMatcher.group(1) : taskMatcher.group(2);
            String funcName = taskMatcher.group(2);
            int line = findLineNumber(text, taskMatcher.start());

            String queueId = "queue:" + (moduleName != null ? moduleName : "") + ":celery:" + taskName;
            nodes.add(createQueueNode(queueId, taskName, funcName, moduleName, filePath, line));

            String methodId = "method:" + filePath + "::" + funcName;
            nodes.add(createMethodNode(methodId, funcName, moduleName, filePath, line));

            edges.add(createConsumesEdge(methodId, queueId));
        }

        Matcher callMatcher = TASK_CALL.matcher(text);
        while (callMatcher.find()) {
            String taskRef = callMatcher.group(1);
            int line = findLineNumber(text, callMatcher.start());

            String queueId = "queue:" + (moduleName != null ? moduleName : "") + ":celery:" + taskRef;
            String callerId = "method:" + filePath + "::caller_l" + line;

            edges.add(createProducesEdge(callerId, queueId));
        }

        return DetectorResult.of(nodes, edges);
    }

    private static CodeNode createQueueNode(String queueId, String taskName, String funcName,
                                            String moduleName, String filePath, int line) {
        CodeNode node = new CodeNode();
        node.setId(queueId);
        node.setKind(NodeKind.QUEUE);
        node.setLabel("celery:" + taskName);
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.getProperties().put("broker", "celery");
        node.getProperties().put("task_name", taskName);
        node.getProperties().put("function", funcName);
        return node;
    }

    private static CodeNode createMethodNode(String methodId, String funcName,
                                             String moduleName, String filePath, int line) {
        CodeNode node = new CodeNode();
        node.setId(methodId);
        node.setKind(NodeKind.METHOD);
        node.setLabel(funcName);
        node.setFqn(filePath + "::" + funcName);
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        return node;
    }

    private static CodeEdge createConsumesEdge(String methodId, String queueId) {
        CodeEdge edge = new CodeEdge();
        edge.setId(methodId + "->consumes->" + queueId);
        edge.setKind(EdgeKind.CONSUMES);
        edge.setSourceId(methodId);
        return edge;
    }

    private static CodeEdge createProducesEdge(String callerId, String queueId) {
        CodeEdge edge = new CodeEdge();
        edge.setId(callerId + "->produces->" + queueId);
        edge.setKind(EdgeKind.PRODUCES);
        edge.setSourceId(callerId);
        return edge;
    }
}
