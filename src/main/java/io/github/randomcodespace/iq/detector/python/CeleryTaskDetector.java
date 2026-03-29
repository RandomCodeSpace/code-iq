package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
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

@Component
public class CeleryTaskDetector extends AbstractAntlrDetector {

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
    public Set<String> getSupportedLanguages() {
        return Set.of("python");
    }

    @Override
    protected ParseTree parse(DetectorContext ctx) {
        return AntlrParserFactory.parse("python", ctx.content());
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
                    CodeNode queueNode = new CodeNode();
                    queueNode.setId(queueId);
                    queueNode.setKind(NodeKind.QUEUE);
                    queueNode.setLabel("celery:" + taskName);
                    queueNode.setModule(moduleName);
                    queueNode.setFilePath(filePath);
                    queueNode.setLineStart(line);
                    queueNode.getProperties().put("broker", "celery");
                    queueNode.getProperties().put("task_name", taskName);
                    queueNode.getProperties().put("function", funcName);
                    nodes.add(queueNode);

                    String methodId = "method:" + filePath + "::" + funcName;
                    CodeNode methodNode = new CodeNode();
                    methodNode.setId(methodId);
                    methodNode.setKind(NodeKind.METHOD);
                    methodNode.setLabel(funcName);
                    methodNode.setFqn(filePath + "::" + funcName);
                    methodNode.setModule(moduleName);
                    methodNode.setFilePath(filePath);
                    methodNode.setLineStart(line);
                    nodes.add(methodNode);

                    CodeEdge consumesEdge = new CodeEdge();
                    consumesEdge.setId(methodId + "->consumes->" + queueId);
                    consumesEdge.setKind(EdgeKind.CONSUMES);
                    consumesEdge.setSourceId(methodId);
                    edges.add(consumesEdge);
                }
            }

            @Override
            public void enterAtom_expr(Python3Parser.Atom_exprContext atomExpr) {
                // Detect task.delay() / task.apply_async() calls
                String exprText = atomExpr.getText();
                Matcher callMatcher = TASK_CALL.matcher(exprText);
                if (callMatcher.find()) {
                    String taskRef = callMatcher.group(1);
                    int line = lineOf(atomExpr);

                    String queueId = "queue:" + (moduleName != null ? moduleName : "") + ":celery:" + taskRef;
                    String callerId = "method:" + filePath + "::caller_l" + line;

                    CodeEdge producesEdge = new CodeEdge();
                    producesEdge.setId(callerId + "->produces->" + queueId);
                    producesEdge.setKind(EdgeKind.PRODUCES);
                    producesEdge.setSourceId(callerId);
                    edges.add(producesEdge);
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
            CodeNode queueNode = new CodeNode();
            queueNode.setId(queueId);
            queueNode.setKind(NodeKind.QUEUE);
            queueNode.setLabel("celery:" + taskName);
            queueNode.setModule(moduleName);
            queueNode.setFilePath(filePath);
            queueNode.setLineStart(line);
            queueNode.getProperties().put("broker", "celery");
            queueNode.getProperties().put("task_name", taskName);
            queueNode.getProperties().put("function", funcName);
            nodes.add(queueNode);

            String methodId = "method:" + filePath + "::" + funcName;
            CodeNode methodNode = new CodeNode();
            methodNode.setId(methodId);
            methodNode.setKind(NodeKind.METHOD);
            methodNode.setLabel(funcName);
            methodNode.setFqn(filePath + "::" + funcName);
            methodNode.setModule(moduleName);
            methodNode.setFilePath(filePath);
            methodNode.setLineStart(line);
            nodes.add(methodNode);

            CodeEdge consumesEdge = new CodeEdge();
            consumesEdge.setId(methodId + "->consumes->" + queueId);
            consumesEdge.setKind(EdgeKind.CONSUMES);
            consumesEdge.setSourceId(methodId);
            edges.add(consumesEdge);
        }

        Matcher callMatcher = TASK_CALL.matcher(text);
        while (callMatcher.find()) {
            String taskRef = callMatcher.group(1);
            int line = findLineNumber(text, callMatcher.start());

            String queueId = "queue:" + (moduleName != null ? moduleName : "") + ":celery:" + taskRef;
            String callerId = "method:" + filePath + "::caller_l" + line;

            CodeEdge producesEdge = new CodeEdge();
            producesEdge.setId(callerId + "->produces->" + queueId);
            producesEdge.setKind(EdgeKind.PRODUCES);
            producesEdge.setSourceId(callerId);
            edges.add(producesEdge);
        }

        return DetectorResult.of(nodes, edges);
    }
}
