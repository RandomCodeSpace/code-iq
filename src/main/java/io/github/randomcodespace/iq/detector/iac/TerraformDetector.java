package io.github.randomcodespace.iq.detector.iac;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;

@DetectorInfo(
    name = "terraform",
    category = "infra",
    description = "Detects Terraform resources, data sources, modules, and variables",
    languages = {"terraform"},
    nodeKinds = {NodeKind.CONFIG_DEFINITION, NodeKind.INFRA_RESOURCE, NodeKind.MODULE},
    edgeKinds = {EdgeKind.DEPENDS_ON},
    properties = {"config_type", "data_source", "provider", "resource_type"}
)
@Component
public class TerraformDetector extends AbstractRegexDetector {
    private static final String PROP_PROVIDER = "provider";
    private static final String PROP_RESOURCE_TYPE = "resource_type";


    private static final Pattern RESOURCE_RE = Pattern.compile("resource\\s+\"([^\"]+)\"\\s+\"([^\"]+)\"");
    private static final Pattern DATA_RE = Pattern.compile("data\\s+\"([^\"]+)\"\\s+\"([^\"]+)\"");
    private static final Pattern MODULE_RE = Pattern.compile("module\\s+\"([^\"]+)\"");
    private static final Pattern VARIABLE_RE = Pattern.compile("variable\\s+\"([^\"]+)\"");
    private static final Pattern OUTPUT_RE = Pattern.compile("output\\s+\"([^\"]+)\"");
    private static final Pattern PROVIDER_RE = Pattern.compile("provider\\s+\"([^\"]+)\"");
    private static final Pattern SOURCE_RE = Pattern.compile("source\\s*=\\s*\"([^\"]+)\"");

    @Override
    public String getName() { return "terraform"; }

    @Override
    public Set<String> getSupportedLanguages() { return Set.of("terraform"); }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        Matcher m = RESOURCE_RE.matcher(text);
        while (m.find()) {
            String resourceType = m.group(1); String resourceName = m.group(2);
            String provider = extractProvider(resourceType);
            CodeNode n = new CodeNode(); n.setId("tf:resource:" + resourceType + ":" + resourceName);
            n.setKind(NodeKind.INFRA_RESOURCE); n.setLabel(resourceType + "." + resourceName);
            n.setFqn(resourceType + "." + resourceName); n.setFilePath(ctx.filePath());
            n.setLineStart(findLineNumber(text, m.start()));
            n.getProperties().put(PROP_RESOURCE_TYPE, resourceType);
            if (provider != null) n.getProperties().put(PROP_PROVIDER, provider);
            nodes.add(n);
        }

        m = DATA_RE.matcher(text);
        while (m.find()) {
            String dataType = m.group(1); String dataName = m.group(2);
            String provider = extractProvider(dataType);
            CodeNode n = new CodeNode(); n.setId("tf:data:" + dataType + ":" + dataName);
            n.setKind(NodeKind.INFRA_RESOURCE); n.setLabel("data." + dataType + "." + dataName);
            n.setFqn("data." + dataType + "." + dataName); n.setFilePath(ctx.filePath());
            n.setLineStart(findLineNumber(text, m.start()));
            n.getProperties().put(PROP_RESOURCE_TYPE, dataType); n.getProperties().put("data_source", true);
            if (provider != null) n.getProperties().put(PROP_PROVIDER, provider);
            nodes.add(n);
        }

        m = MODULE_RE.matcher(text);
        while (m.find()) {
            String moduleName = m.group(1);
            String source = findSourceInBlock(text, m.start());
            CodeNode n = new CodeNode(); n.setId("tf:module:" + moduleName);
            n.setKind(NodeKind.MODULE); n.setLabel("module." + moduleName);
            n.setFqn("module." + moduleName); n.setFilePath(ctx.filePath());
            n.setLineStart(findLineNumber(text, m.start()));
            if (source != null) {
                n.getProperties().put("source", source);
                CodeEdge e = new CodeEdge(); e.setId("tf:module:" + moduleName + ":depends_on:" + source);
                e.setKind(EdgeKind.DEPENDS_ON); e.setSourceId("tf:module:" + moduleName);
                e.setTarget(new CodeNode(source, NodeKind.MODULE, source));
                e.getProperties().put("module_source", source);
                edges.add(e);
            }
            nodes.add(n);
        }

        m = VARIABLE_RE.matcher(text);
        while (m.find()) {
            CodeNode n = new CodeNode(); n.setId("tf:var:" + m.group(1));
            n.setKind(NodeKind.CONFIG_DEFINITION); n.setLabel("var." + m.group(1));
            n.setFqn("var." + m.group(1)); n.setFilePath(ctx.filePath());
            n.setLineStart(findLineNumber(text, m.start()));
            n.getProperties().put("config_type", "variable"); nodes.add(n);
        }

        m = OUTPUT_RE.matcher(text);
        while (m.find()) {
            CodeNode n = new CodeNode(); n.setId("tf:output:" + m.group(1));
            n.setKind(NodeKind.CONFIG_DEFINITION); n.setLabel("output." + m.group(1));
            n.setFqn("output." + m.group(1)); n.setFilePath(ctx.filePath());
            n.setLineStart(findLineNumber(text, m.start()));
            n.getProperties().put("config_type", "output"); nodes.add(n);
        }

        m = PROVIDER_RE.matcher(text);
        while (m.find()) {
            CodeNode n = new CodeNode(); n.setId("tf:provider:" + m.group(1));
            n.setKind(NodeKind.INFRA_RESOURCE); n.setLabel("provider." + m.group(1));
            n.setFqn("provider." + m.group(1)); n.setFilePath(ctx.filePath());
            n.setLineStart(findLineNumber(text, m.start()));
            n.getProperties().put(PROP_RESOURCE_TYPE, PROP_PROVIDER); n.getProperties().put(PROP_PROVIDER, m.group(1));
            nodes.add(n);
        }

        return DetectorResult.of(nodes, edges);
    }

    private static String extractProvider(String resourceType) {
        String[] parts = resourceType.split("_", 2);
        return parts.length > 1 ? parts[0] : null;
    }

    private static String findSourceInBlock(String text, int blockStart) {
        int bracePos = text.indexOf('{', blockStart);
        if (bracePos == -1) return null;
        String snippet = text.substring(bracePos, Math.min(text.length(), bracePos + 500));
        Matcher m = SOURCE_RE.matcher(snippet);
        return m.find() ? m.group(1) : null;
    }
}
