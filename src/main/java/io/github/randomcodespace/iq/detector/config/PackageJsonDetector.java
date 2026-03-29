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

/**
 * Detects module dependencies and scripts from package.json files.
 */
@Component
public class PackageJsonDetector extends AbstractStructuredDetector {

    @Override
    public String getName() {
        return "package_json";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("json");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String fp = ctx.filePath();
        if (fp == null || !basename(fp).equals("package.json")) {
            return DetectorResult.empty();
        }

        Object parsedData = ctx.parsedData();
        if (parsedData == null) return DetectorResult.empty();

        Map<String, Object> pkg = getMap(parsedData, "data");
        if (pkg.isEmpty()) return DetectorResult.empty();

        String filepath = ctx.filePath();
        String moduleId = "npm:" + filepath;
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String pkgName = getString(pkg, "name");
        if (pkgName == null) pkgName = filepath;

        Map<String, Object> props = new HashMap<>();
        props.put("package_name", pkgName);
        String version = getString(pkg, "version");
        if (version != null) props.put("version", version);

        CodeNode moduleNode = new CodeNode(moduleId, NodeKind.MODULE, pkgName);
        moduleNode.setFqn(pkgName);
        moduleNode.setModule(ctx.moduleName());
        moduleNode.setFilePath(filepath);
        moduleNode.setProperties(props);
        nodes.add(moduleNode);

        // DEPENDS_ON edges for dependencies and devDependencies
        for (String depKey : List.of("dependencies", "devDependencies")) {
            Map<String, Object> deps = getMap(pkg, depKey);
            for (var depEntry : deps.entrySet()) {
                String depName = depEntry.getKey();
                Map<String, Object> edgeProps = new HashMap<>();
                edgeProps.put("dep_type", depKey);
                Object depVersion = depEntry.getValue();
                if (depVersion instanceof String s) {
                    edgeProps.put("version_spec", s);
                }

                CodeEdge edge = new CodeEdge();
                edge.setId(moduleId + "->npm:" + depName);
                edge.setKind(EdgeKind.DEPENDS_ON);
                edge.setSourceId(moduleId);
                edge.setTarget(new CodeNode("npm:" + depName, null, null));
                edge.setProperties(edgeProps);
                edges.add(edge);
            }
        }

        // METHOD nodes for each script
        Map<String, Object> scripts = getMap(pkg, "scripts");
        for (var scriptEntry : scripts.entrySet()) {
            String scriptName = scriptEntry.getKey();
            String scriptId = "npm:" + filepath + ":script:" + scriptName;
            Map<String, Object> scriptProps = new HashMap<>();
            scriptProps.put("script_name", scriptName);
            Object scriptCmd = scriptEntry.getValue();
            if (scriptCmd instanceof String s) {
                scriptProps.put("command", s);
            }

            CodeNode scriptNode = new CodeNode(scriptId, NodeKind.METHOD,
                    "npm run " + scriptName);
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

    private String basename(String path) {
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
