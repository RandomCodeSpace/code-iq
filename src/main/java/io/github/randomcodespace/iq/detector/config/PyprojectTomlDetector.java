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
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

/**
 * Detects Python project metadata, dependencies, and entry points from pyproject.toml.
 * <p>
 * Expects parsedData to be a Map with type "toml" and "data" containing the parsed TOML structure.
 * Since Java doesn't have a built-in TOML parser, this detector works with pre-parsed data.
 */
@DetectorInfo(
    name = "pyproject_toml",
    category = "config",
    description = "Detects pyproject.toml project metadata, dependencies, and build targets",
    parser = ParserType.STRUCTURED,
    languages = {"toml"},
    nodeKinds = {NodeKind.CONFIG_DEFINITION, NodeKind.MODULE},
    edgeKinds = {EdgeKind.CONTAINS, EdgeKind.DEPENDS_ON},
    properties = {"dependencies", "target", "version"}
)
@Component
public class PyprojectTomlDetector extends AbstractStructuredDetector {
    private static final String PROP_DESCRIPTION = "description";
    private static final String PROP_VERSION = "version";


    @Override
    public String getName() {
        return "pyproject_toml";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("toml");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String fp = ctx.filePath();
        if (fp == null || !basename(fp).equals("pyproject.toml")) {
            return DetectorResult.empty();
        }

        Object parsedData = ctx.parsedData();
        if (parsedData == null) return DetectorResult.empty();

        Map<String, Object> pd = asMap(parsedData);
        Map<String, Object> data = getMap(pd, "data");
        if (data.isEmpty()) return DetectorResult.empty();

        String filepath = ctx.filePath();
        String moduleId = "pypi:" + filepath;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        Map<String, Object> projectSection = getMap(data, "project");
        Map<String, Object> toolSection = getMap(data, "tool");
        Map<String, Object> poetrySection = getMap(toolSection, "poetry");

        // Resolve project name
        String pkgName = getString(projectSection, "name");
        if (pkgName == null) pkgName = getString(poetrySection, "name");
        if (pkgName == null) pkgName = filepath;

        Map<String, Object> props = new HashMap<>();
        props.put("package_name", pkgName);
        String version = getString(projectSection, PROP_VERSION);
        if (version == null) version = getString(poetrySection, PROP_VERSION);
        if (version != null) props.put(PROP_VERSION, version);
        String description = getString(projectSection, PROP_DESCRIPTION);
        if (description == null) description = getString(poetrySection, PROP_DESCRIPTION);
        if (description != null) props.put(PROP_DESCRIPTION, description);

        CodeNode moduleNode = new CodeNode(moduleId, NodeKind.MODULE, pkgName);
        moduleNode.setFqn(pkgName);
        moduleNode.setModule(ctx.moduleName());
        moduleNode.setFilePath(filepath);
        moduleNode.setProperties(props);
        nodes.add(moduleNode);

        // PEP 621 style: [project].dependencies is a list of strings
        List<Object> pep621Deps = getList(projectSection, "dependencies");
        for (Object depSpec : pep621Deps) {
            if (!(depSpec instanceof String s)) continue;
            String depName = parseDepName(s);
            if (depName != null) {
                CodeEdge edge = new CodeEdge();
                edge.setId(moduleId + "->pypi:" + depName);
                edge.setKind(EdgeKind.DEPENDS_ON);
                edge.setSourceId(moduleId);
                edge.setTarget(new CodeNode("pypi:" + depName, null, null));
                edge.setProperties(Map.of("dep_spec", s));
                edges.add(edge);
            }
        }

        // Poetry style: [tool.poetry].dependencies is a dict
        Map<String, Object> poetryDeps = getMap(poetrySection, "dependencies");
        for (var depEntry : poetryDeps.entrySet()) {
            String depName = depEntry.getKey();
            if ("python".equalsIgnoreCase(depName)) continue;
            Map<String, Object> edgeProps = new HashMap<>();
            if (depEntry.getValue() instanceof String s) {
                edgeProps.put("version_spec", s);
            }
            CodeEdge edge = new CodeEdge();
            edge.setId(moduleId + "->pypi:" + depName);
            edge.setKind(EdgeKind.DEPENDS_ON);
            edge.setSourceId(moduleId);
            edge.setTarget(new CodeNode("pypi:" + depName, null, null));
            edge.setProperties(edgeProps);
            edges.add(edge);
        }

        // CONFIG_DEFINITION nodes for entry points / scripts
        Map<String, Object> scripts = getMap(projectSection, "scripts");
        Map<String, Object> poetryScripts = getMap(poetrySection, "scripts");
        Map<String, Object> allScripts = new HashMap<>(scripts);
        allScripts.putAll(poetryScripts);

        String finalPkgName = pkgName;
        for (var scriptEntry : allScripts.entrySet()) {
            String scriptName = scriptEntry.getKey();
            String scriptId = "pypi:" + filepath + ":script:" + scriptName;
            Map<String, Object> scriptProps = new HashMap<>();
            scriptProps.put("script_name", scriptName);
            if (scriptEntry.getValue() instanceof String s) {
                scriptProps.put("target", s);
            }

            CodeNode scriptNode = new CodeNode(scriptId, NodeKind.CONFIG_DEFINITION, scriptName);
            scriptNode.setFqn(finalPkgName + ":script:" + scriptName);
            scriptNode.setModule(ctx.moduleName());
            scriptNode.setFilePath(filepath);
            scriptNode.setProperties(scriptProps);
            nodes.add(scriptNode);

            CodeEdge edge = new CodeEdge();
            edge.setId(moduleId + "->" + scriptId);
            edge.setKind(EdgeKind.CONTAINS);
            edge.setSourceId(moduleId);
            edge.setTarget(new CodeNode(scriptId, null, null));
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }

    static String parseDepName(String spec) {
        if (spec == null || spec.isBlank()) return null;
        spec = spec.strip();
        String name = spec;
        for (char ch : new char[]{'>', '=', '<', '!', '[', ';', '@', ' '}) {
            int idx = name.indexOf(ch);
            if (idx > 0) {
                name = name.substring(0, idx);
            }
        }
        name = name.strip();
        return name.isEmpty() ? null : name;
    }

    private String basename(String path) {
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
