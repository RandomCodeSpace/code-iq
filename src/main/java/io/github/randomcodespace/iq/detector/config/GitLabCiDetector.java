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
 * Detects stages, jobs, dependencies, and tool usage from GitLab CI YAML files.
 */
@DetectorInfo(
    name = "gitlab_ci",
    category = "config",
    description = "Detects GitLab CI pipeline stages, jobs, and dependencies",
    parser = ParserType.STRUCTURED,
    languages = {"yaml"},
    nodeKinds = {NodeKind.CONFIG_KEY, NodeKind.METHOD, NodeKind.MODULE},
    edgeKinds = {EdgeKind.CONTAINS, EdgeKind.DEPENDS_ON, EdgeKind.EXTENDS, EdgeKind.IMPORTS},
    properties = {"image", "stages"}
)
@Component
public class GitLabCiDetector extends AbstractStructuredDetector {
    private static final String PROP_IMAGE = "image";
    private static final String PROP_STAGE = "stage";


    private static final Set<String> GITLAB_CI_KEYWORDS = Set.of(
            "stages", "variables", "default", "workflow", "include",
            PROP_IMAGE, "services", "before_script", "after_script", "cache");

    private static final List<String> TOOL_KEYWORDS = List.of(
            "docker", "helm", "kubectl", "terraform", "maven", "gradle", "npm", "pip");

    @Override
    public String getName() {
        return "gitlab_ci";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("yaml");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        if (!ctx.filePath().endsWith(".gitlab-ci.yml")) {
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
        String pipelineId = "gitlab:" + fp + ":pipeline";
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Pipeline MODULE node
        CodeNode pipelineNode = new CodeNode(pipelineId, NodeKind.MODULE, "pipeline:" + fp);
        pipelineNode.setFqn(pipelineId);
        pipelineNode.setModule(ctx.moduleName());
        pipelineNode.setFilePath(fp);
        pipelineNode.setProperties(Map.of("pipeline_file", fp));
        nodes.add(pipelineNode);

        // Stages
        List<Object> stages = getList(data, "stages");
        for (Object stageName : stages) {
            String stageStr = String.valueOf(stageName);
            CodeNode stageNode = new CodeNode("gitlab:" + fp + ":stage:" + stageStr,
                    NodeKind.CONFIG_KEY, "stage:" + stageStr);
            stageNode.setModule(ctx.moduleName());
            stageNode.setFilePath(fp);
            stageNode.setProperties(Map.of(PROP_STAGE, stageStr));
            nodes.add(stageNode);
        }

        // Include directives
        Object includes = data.get("include");
        if (includes != null) {
            List<Object> includeList;
            if (includes instanceof String s) {
                includeList = List.of(s);
            } else if (includes instanceof List<?> list) {
                includeList = new ArrayList<>(list);
            } else {
                includeList = List.of();
            }
            for (Object inc : includeList) {
                String target;
                if (inc instanceof String s) {
                    target = s;
                } else if (inc instanceof Map<?, ?> incMap) {
                    Object local = incMap.get("local");
                    if (local != null) target = String.valueOf(local);
                    else {
                        Object file = incMap.get("file");
                        if (file != null) target = String.valueOf(file);
                        else {
                            Object template = incMap.get("template");
                            target = template != null ? String.valueOf(template) : String.valueOf(inc);
                        }
                    }
                } else {
                    target = String.valueOf(inc);
                }
                CodeEdge edge = new CodeEdge();
                edge.setId(pipelineId + "->" + target);
                edge.setKind(EdgeKind.IMPORTS);
                edge.setSourceId(pipelineId);
                edge.setTarget(new CodeNode(target, null, null));
                edges.add(edge);
            }
        }

        // Collect job names
        List<String> jobNames = new ArrayList<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            if (GITLAB_CI_KEYWORDS.contains(key)) continue;
            if (entry.getValue() instanceof Map<?, ?>) {
                jobNames.add(key);
            }
        }

        Map<String, String> jobIds = new LinkedHashMap<>();
        for (String name : jobNames) {
            jobIds.put(name, "gitlab:" + fp + ":job:" + name);
        }

        // Process each job
        for (String jobName : jobNames) {
            Map<String, Object> jobDef = asMap(data.get(jobName));
            String jobId = jobIds.get(jobName);

            Map<String, Object> props = new HashMap<>();
            String stageVal = getString(jobDef, PROP_STAGE);
            if (stageVal != null) props.put(PROP_STAGE, stageVal);
            String imageVal = getString(jobDef, PROP_IMAGE);
            if (imageVal != null) props.put(PROP_IMAGE, imageVal);

            List<Object> scripts = getList(jobDef, "script");
            List<String> tools = detectTools(scripts);
            if (!tools.isEmpty()) props.put("tools", tools);

            CodeNode jobNode = new CodeNode(jobId, NodeKind.METHOD, jobName);
            jobNode.setFqn(jobId);
            jobNode.setModule(ctx.moduleName());
            jobNode.setFilePath(fp);
            jobNode.setProperties(props);
            nodes.add(jobNode);

            // CONTAINS edge: pipeline -> job
            edges.add(createEdge(pipelineId, jobId, EdgeKind.CONTAINS,
                    "pipeline contains job " + jobName));

            // needs: dependencies
            Object needs = jobDef.get("needs");
            List<String> needsList = toDepList(needs);
            for (String dep : needsList) {
                if (jobIds.containsKey(dep)) {
                    edges.add(createEdge(jobId, jobIds.get(dep), EdgeKind.DEPENDS_ON,
                            "job " + jobName + " needs " + dep));
                }
            }

            // extends: template inheritance
            Object extendsVal = jobDef.get("extends");
            List<String> extendsList = toStringList(extendsVal);
            for (String parent : extendsList) {
                if (jobIds.containsKey(parent)) {
                    edges.add(createEdge(jobId, jobIds.get(parent), EdgeKind.EXTENDS,
                            "job " + jobName + " extends " + parent));
                }
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private List<String> detectTools(List<Object> scripts) {
        List<String> tools = new ArrayList<>();
        for (Object line : scripts) {
            String lineStr = String.valueOf(line);
            for (String tool : TOOL_KEYWORDS) {
                if (lineStr.contains(tool) && !tools.contains(tool)) {
                    tools.add(tool);
                }
            }
        }
        return tools;
    }

    private List<String> toDepList(Object obj) {
        if (obj instanceof String s) return List.of(s);
        if (obj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object job = m.get("job");
                    if (job != null) result.add(String.valueOf(job));
                } else {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }

    private List<String> toStringList(Object obj) {
        if (obj instanceof String s) return List.of(s);
        if (obj instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
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
