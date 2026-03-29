package io.github.randomcodespace.iq.detector.java;

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

/**
 * Detects Java RMI interfaces and remote object exports.
 */
@Component
public class RmiDetector extends AbstractRegexDetector {

    private static final Pattern REMOTE_INTERFACE_RE = Pattern.compile(
            "interface\\s+(\\w+)\\s+extends\\s+(?:java\\.rmi\\.)?Remote");
    private static final Pattern UNICAST_RE = Pattern.compile(
            "class\\s+(\\w+)\\s+extends\\s+(?:java\\.rmi\\.server\\.)?UnicastRemoteObject");
    private static final Pattern IMPLEMENTS_RE = Pattern.compile(
            "class\\s+(\\w+)\\s+extends\\s+\\w+\\s+implements\\s+([\\w,\\s]+)");
    private static final Pattern REGISTRY_BIND_RE = Pattern.compile(
            "(?:Registry|Naming)\\s*\\.(?:bind|rebind)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern REGISTRY_LOOKUP_RE = Pattern.compile(
            "(?:Registry|Naming)\\s*\\.lookup\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern CLASS_FIND_RE = Pattern.compile("class\\s+(\\w+)");

    @Override
    public String getName() {
        return "rmi";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        boolean hasRemote = text.contains("Remote");
        boolean hasUnicast = text.contains("UnicastRemoteObject");
        boolean hasNaming = text.contains("Naming.") || text.contains("Registry.");
        if (!hasRemote && !hasUnicast && !hasNaming) return DetectorResult.empty();

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Remote interfaces
        for (int i = 0; i < lines.length; i++) {
            Matcher m = REMOTE_INTERFACE_RE.matcher(lines[i]);
            if (m.find()) {
                String ifaceName = m.group(1);
                String ifaceId = ctx.filePath() + ":" + ifaceName;
                CodeNode node = new CodeNode();
                node.setId(ifaceId);
                node.setKind(NodeKind.RMI_INTERFACE);
                node.setLabel(ifaceName);
                node.setFqn(ifaceName);
                node.setFilePath(ctx.filePath());
                node.setLineStart(i + 1);
                node.getProperties().put("type", "remote_interface");
                nodes.add(node);
            }
        }

        // UnicastRemoteObject implementations
        for (int i = 0; i < lines.length; i++) {
            Matcher m = UNICAST_RE.matcher(lines[i]);
            if (m.find()) {
                String cn = m.group(1);
                String classId = ctx.filePath() + ":" + cn;
                Matcher implMatch = IMPLEMENTS_RE.matcher(lines[i]);
                if (implMatch.find()) {
                    String[] impls = implMatch.group(2).split(",");
                    for (String iface : impls) {
                        String ifaceName = iface.trim();
                        CodeEdge edge = new CodeEdge();
                        edge.setId(classId + "->exports_rmi->*:" + ifaceName);
                        edge.setKind(EdgeKind.EXPORTS_RMI);
                        edge.setSourceId(classId);
                        edge.setTarget(new CodeNode("*:" + ifaceName, NodeKind.RMI_INTERFACE, ifaceName));
                        edges.add(edge);
                    }
                }
            }
        }

        // Registry bindings
        for (int i = 0; i < lines.length; i++) {
            Matcher m = REGISTRY_BIND_RE.matcher(lines[i]);
            if (m.find()) {
                String bindingName = m.group(1);
                String cn = findEnclosingClass(lines, i);
                if (cn != null) {
                    String classId = ctx.filePath() + ":" + cn;
                    CodeEdge edge = new CodeEdge();
                    edge.setId(classId + "->exports_rmi->rmi:binding:" + bindingName);
                    edge.setKind(EdgeKind.EXPORTS_RMI);
                    edge.setSourceId(classId);
                    edge.setTarget(new CodeNode("rmi:binding:" + bindingName, NodeKind.RMI_INTERFACE, bindingName));
                    edge.setProperties(Map.of("binding_name", bindingName));
                    edges.add(edge);
                }
            }
        }

        // Registry lookups
        for (int i = 0; i < lines.length; i++) {
            Matcher m = REGISTRY_LOOKUP_RE.matcher(lines[i]);
            if (m.find()) {
                String bindingName = m.group(1);
                String cn = findEnclosingClass(lines, i);
                if (cn != null) {
                    String classId = ctx.filePath() + ":" + cn;
                    CodeEdge edge = new CodeEdge();
                    edge.setId(classId + "->invokes_rmi->rmi:binding:" + bindingName);
                    edge.setKind(EdgeKind.INVOKES_RMI);
                    edge.setSourceId(classId);
                    edge.setTarget(new CodeNode("rmi:binding:" + bindingName, NodeKind.RMI_INTERFACE, bindingName));
                    edge.setProperties(Map.of("binding_name", bindingName));
                    edges.add(edge);
                }
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private String findEnclosingClass(String[] lines, int lineIdx) {
        for (int i = lineIdx; i >= 0; i--) {
            Matcher m = CLASS_FIND_RE.matcher(lines[i]);
            if (m.find()) return m.group(1);
        }
        return null;
    }
}
