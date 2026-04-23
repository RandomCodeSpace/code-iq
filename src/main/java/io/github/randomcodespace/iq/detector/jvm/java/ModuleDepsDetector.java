package io.github.randomcodespace.iq.detector.jvm.java;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
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

/**
 * Detects Maven/Gradle module declarations and inter-module dependencies.
 */
@DetectorInfo(
    name = "module_deps",
    category = "config",
    description = "Detects Maven/Gradle module dependencies and build structure",
    languages = {"java", "xml", "gradle"},
    nodeKinds = {NodeKind.MODULE},
    edgeKinds = {EdgeKind.CONTAINS, EdgeKind.DEPENDS_ON},
    properties = {"group_id"}
)
@Component
public class ModuleDepsDetector extends AbstractRegexDetector {
    private static final String PROP_BUILD_TOOL = "build_tool";
    private static final String PROP_GRADLE = "gradle";
    private static final String PROP_UNKNOWN = "unknown";


    private static final Pattern GRADLE_DEPENDENCY_RE = Pattern.compile(
            "(?:implementation|api|compile|compileOnly|runtimeOnly|testImplementation)\\s+"
                    + "(?:project\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)"
                    + "|['\"]([^'\"]+)['\"])");
    private static final Pattern GRADLE_SETTINGS_MODULE_RE = Pattern.compile("include\\s+['\"]([^'\"]+)['\"]");

    // Simple XML patterns for pom.xml
    private static final Pattern GROUP_ID_RE = Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern ARTIFACT_ID_RE = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern MODULE_RE = Pattern.compile("<module>([^<]+)</module>");
    private static final Pattern DEPENDENCY_BLOCK_RE = Pattern.compile(
            "<dependency>\\s*(.*?)\\s*</dependency>", Pattern.DOTALL);

    @Override
    public String getName() {
        return "module_deps";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java", "xml", PROP_GRADLE);
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String filePath = ctx.filePath();
        if (filePath.endsWith("pom.xml")) {
            return detectMaven(ctx);
        }
        // Order matters: `settings.gradle[.kts]` must be matched before the generic
        // `.gradle[.kts]` branch, otherwise Gradle multi-module settings files are
        // misrouted to detectGradle() and never reach detectGradleSettings().
        if (filePath.endsWith("settings.gradle") || filePath.endsWith("settings.gradle.kts")) {
            return detectGradleSettings(ctx);
        }
        if (filePath.endsWith(".gradle") || filePath.endsWith(".gradle.kts")) {
            return detectGradle(ctx);
        }
        return DetectorResult.empty();
    }

    private DetectorResult detectMaven(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Extract top-level groupId and artifactId (before first <dependencies>)
        String topSection = text;
        int depsIdx = text.indexOf("<dependencies>");
        if (depsIdx > 0) topSection = text.substring(0, depsIdx);

        Matcher gm = GROUP_ID_RE.matcher(topSection);
        String groupId = gm.find() ? gm.group(1) : PROP_UNKNOWN;
        Matcher am = ARTIFACT_ID_RE.matcher(topSection);
        String artifactId = am.find() ? am.group(1) : PROP_UNKNOWN;

        String moduleId = "module:" + groupId + ":" + artifactId;
        CodeNode moduleNode = new CodeNode();
        moduleNode.setId(moduleId);
        moduleNode.setKind(NodeKind.MODULE);
        moduleNode.setLabel(artifactId);
        moduleNode.setFqn(groupId + ":" + artifactId);
        moduleNode.setFilePath(ctx.filePath());
        moduleNode.setLineStart(1);
        moduleNode.getProperties().put("group_id", groupId);
        moduleNode.getProperties().put("artifact_id", artifactId);
        moduleNode.getProperties().put(PROP_BUILD_TOOL, "maven");
        nodes.add(moduleNode);

        // Sub-modules
        for (Matcher mm = MODULE_RE.matcher(text); mm.find(); ) {
            String subModule = mm.group(1);
            String subId = "module:" + groupId + ":" + subModule;
            CodeNode subNode = new CodeNode();
            subNode.setId(subId);
            subNode.setKind(NodeKind.MODULE);
            subNode.setLabel(subModule);
            subNode.setFqn(groupId + ":" + subModule);
            subNode.getProperties().put(PROP_BUILD_TOOL, "maven");
            subNode.getProperties().put("parent", artifactId);
            nodes.add(subNode);

            CodeEdge edge = new CodeEdge();
            edge.setId(moduleId + "->contains->" + subId);
            edge.setKind(EdgeKind.CONTAINS);
            edge.setSourceId(moduleId);
            edge.setTarget(subNode);
            edges.add(edge);
        }

        // Dependencies
        for (Matcher dm = DEPENDENCY_BLOCK_RE.matcher(text); dm.find(); ) {
            String block = dm.group(1);
            Matcher dg = GROUP_ID_RE.matcher(block);
            Matcher da = ARTIFACT_ID_RE.matcher(block);
            if (da.find()) {
                String depGroup = dg.find() ? dg.group(1) : PROP_UNKNOWN;
                String depArtifact = da.group(1);
                String depId = "module:" + depGroup + ":" + depArtifact;
                CodeEdge edge = new CodeEdge();
                edge.setId(moduleId + "->depends_on->" + depId);
                edge.setKind(EdgeKind.DEPENDS_ON);
                edge.setSourceId(moduleId);
                edge.setTarget(new CodeNode(depId, NodeKind.MODULE, depArtifact));
                edge.setProperties(Map.of("group_id", depGroup, "artifact_id", depArtifact));
                edges.add(edge);
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private DetectorResult detectGradle(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String moduleName = ctx.moduleName();
        if (moduleName == null || moduleName.isEmpty()) {
            String fp = ctx.filePath();
            int lastSlash = fp.lastIndexOf('/');
            if (lastSlash > 0) {
                String dir = fp.substring(0, lastSlash);
                int prevSlash = dir.lastIndexOf('/');
                moduleName = prevSlash >= 0 ? dir.substring(prevSlash + 1) : dir;
            } else {
                moduleName = fp;
            }
        }
        String moduleId = "module:" + moduleName;

        CodeNode moduleNode = new CodeNode();
        moduleNode.setId(moduleId);
        moduleNode.setKind(NodeKind.MODULE);
        moduleNode.setLabel(moduleName);
        moduleNode.setFqn(moduleName);
        moduleNode.setFilePath(ctx.filePath());
        moduleNode.setLineStart(1);
        moduleNode.getProperties().put(PROP_BUILD_TOOL, PROP_GRADLE);
        nodes.add(moduleNode);

        for (int i = 0; i < lines.length; i++) {
            Matcher m = GRADLE_DEPENDENCY_RE.matcher(lines[i]);
            if (!m.find()) continue;

            String projectDep = m.group(1);
            String externalDep = m.group(2);

            if (projectDep != null) {
                String depName = projectDep.replaceAll("^:", "");
                String depId = "module:" + depName;
                CodeEdge edge = new CodeEdge();
                edge.setId(moduleId + "->depends_on->" + depId);
                edge.setKind(EdgeKind.DEPENDS_ON);
                edge.setSourceId(moduleId);
                edge.setTarget(new CodeNode(depId, NodeKind.MODULE, depName));
                edge.setProperties(Map.of("type", "project"));
                edges.add(edge);
            } else if (externalDep != null && externalDep.contains(":")) {
                String[] parts = externalDep.split(":");
                String depId = parts.length >= 2 ? "module:" + parts[0] + ":" + parts[1] : "module:" + externalDep;
                CodeEdge edge = new CodeEdge();
                edge.setId(moduleId + "->depends_on->" + depId);
                edge.setKind(EdgeKind.DEPENDS_ON);
                edge.setSourceId(moduleId);
                edge.setTarget(new CodeNode(depId, NodeKind.MODULE, externalDep));
                edge.setProperties(Map.of("coordinate", externalDep, "type", "external"));
                edges.add(edge);
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private DetectorResult detectGradleSettings(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        List<CodeNode> nodes = new ArrayList<>();
        for (Matcher m = GRADLE_SETTINGS_MODULE_RE.matcher(text); m.find(); ) {
            String modulePath = m.group(1).replaceAll("^:", "");
            String moduleId = "module:" + modulePath;
            CodeNode node = new CodeNode();
            node.setId(moduleId);
            node.setKind(NodeKind.MODULE);
            node.setLabel(modulePath);
            node.setFqn(modulePath);
            node.setFilePath(ctx.filePath());
            node.getProperties().put(PROP_BUILD_TOOL, PROP_GRADLE);
            nodes.add(node);
        }

        return DetectorResult.of(nodes, List.of());
    }
}
