package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.AbstractStructuredDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

/**
 * Detects workflows, jobs, triggers, and job dependencies from GitHub Actions YAML files.
 */
@DetectorInfo(
    name = "github_actions",
    category = "config",
    description = "Detects GitHub Actions workflows, jobs, and steps",
    parser = ParserType.STRUCTURED,
    languages = {"yaml"},
    nodeKinds = {NodeKind.CONFIG_KEY, NodeKind.METHOD, NodeKind.MODULE},
    edgeKinds = {EdgeKind.CONTAINS, EdgeKind.DEPENDS_ON}
)
@Component
public class GitHubActionsDetector extends AbstractStructuredDetector {

    @Override
    public String getName() {
        return "github_actions";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("yaml");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        if (!ctx.filePath().contains(".github/workflows/")) {
            return DetectorResult.empty();
        }

        Object parsedData = ctx.parsedData();
        if (parsedData == null) {
            return DetectorResult.empty();
        }

        Map<String, Object> data = getMap(parsedData, "data");
        if (data.isEmpty()) {
            return DetectorResult.empty();
        }

        String fp = ctx.filePath();
        String workflowId = "gha:" + fp;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Workflow MODULE node
        String workflowName = getStringOrDefault(data, "name", fp);

        CodeNode workflowNode = new CodeNode(workflowId, NodeKind.MODULE, workflowName);
        workflowNode.setFqn(workflowId);
        workflowNode.setModule(ctx.moduleName());
        workflowNode.setFilePath(fp);
        workflowNode.setProperties(Map.of("workflow_file", fp));
        nodes.add(workflowNode);

        // Trigger events from "on:" key
        // YAML parses bare "on" as Boolean.TRUE
        Object onTriggers = data.get("on");
        if (onTriggers == null) {
            // SnakeYAML may parse bare 'on' key as Boolean.TRUE — search by entry value
            onTriggers = data.entrySet().stream()
                    .filter(e -> Boolean.TRUE.equals(e.getKey()))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (onTriggers == null) {
            onTriggers = data.get("true");
        }

        if (onTriggers instanceof String triggerStr) {
            nodes.add(createTriggerNode(fp, triggerStr, ctx.moduleName(), Map.of("event", triggerStr)));
        } else if (onTriggers instanceof List<?> triggerList) {
            for (Object event : triggerList) {
                String eventStr = String.valueOf(event);
                nodes.add(createTriggerNode(fp, eventStr, ctx.moduleName(), Map.of("event", eventStr)));
            }
        } else if (onTriggers instanceof Map<?, ?> triggerMap) {
            for (var trigEntry : triggerMap.entrySet()) {
                String eventStr = String.valueOf(trigEntry.getKey());
                Map<String, Object> props = new HashMap<>();
                props.put("event", eventStr);
                nodes.add(createTriggerNode(fp, eventStr, ctx.moduleName(), props));
            }
        }

        // Jobs
        Map<String, Object> jobs = getMap(data, "jobs");
        if (jobs.isEmpty()) {
            return DetectorResult.of(nodes, edges);
        }

        Map<String, String> jobIds = new LinkedHashMap<>();
        for (String jobName : jobs.keySet()) {
            jobIds.put(jobName, "gha:" + fp + ":job:" + jobName);
        }

        for (var jobEntry : jobs.entrySet()) {
            String jobName = jobEntry.getKey();
            Map<String, Object> jobDef = asMap(jobEntry.getValue());
            if (jobDef.isEmpty()) {
                continue;
            }

            String jobId = jobIds.get(jobName);

            Map<String, Object> props = new HashMap<>();
            Object runsOn = jobDef.get("runs-on");
            if (runsOn != null) {
                props.put("runs_on", String.valueOf(runsOn));
            }

            String jobLabel = getStringOrDefault(jobDef, "name", jobName);

            CodeNode jobNode = new CodeNode(jobId, NodeKind.METHOD, jobLabel);
            jobNode.setFqn(jobId);
            jobNode.setModule(ctx.moduleName());
            jobNode.setFilePath(fp);
            jobNode.setProperties(props);
            nodes.add(jobNode);

            // CONTAINS edge: workflow -> job
            edges.add(createEdge(workflowId, jobId, EdgeKind.CONTAINS,
                    "workflow contains job " + jobName));

            // Job dependencies via "needs"
            Object needs = jobDef.get("needs");
            List<String> needsList = toStringList(needs);
            for (String dep : needsList) {
                if (jobIds.containsKey(dep)) {
                    edges.add(createEdge(jobId, jobIds.get(dep), EdgeKind.DEPENDS_ON,
                            "job " + jobName + " needs " + dep));
                }
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private CodeNode createTriggerNode(String fp, String eventStr, String moduleName,
                                        Map<String, Object> props) {
        CodeNode node = new CodeNode("gha:" + fp + ":trigger:" + eventStr,
                NodeKind.CONFIG_KEY, "trigger: " + eventStr);
        node.setModule(moduleName);
        node.setFilePath(fp);
        node.setProperties(props);
        return node;
    }

    private List<String> toStringList(Object obj) {
        if (obj instanceof String s) {
            return List.of(s);
        }
        if (obj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return List.of();
    }

    private CodeEdge createEdge(String sourceId, String targetId, EdgeKind kind, String label) {
        CodeEdge edge = new CodeEdge();
        edge.setId(sourceId + "->" + targetId);
        edge.setKind(kind);
        edge.setSourceId(sourceId);
        edge.setTarget(new CodeNode(targetId, null, null));
        return edge;
    }
}
