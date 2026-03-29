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
import java.util.regex.Pattern;

/**
 * Detects services, ports, volumes, networks, and dependencies from Docker Compose files.
 */
@Component
public class DockerComposeDetector extends AbstractStructuredDetector {

    private static final Pattern COMPOSE_FILENAME_RE = Pattern.compile(
            "^(docker-compose|compose).*\\.(yml|yaml)$", Pattern.CASE_INSENSITIVE);

    @Override
    public String getName() {
        return "docker_compose";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("yaml");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        if (!isComposeFile(ctx)) {
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

        Map<String, Object> services = getMap(data, "services");
        if (services.isEmpty()) {
            return DetectorResult.empty();
        }

        String fp = ctx.filePath();
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Build service ID lookup
        Map<String, String> serviceIds = new LinkedHashMap<>();
        for (String svcName : services.keySet()) {
            serviceIds.put(svcName, "compose:" + fp + ":service:" + svcName);
        }

        for (var entry : services.entrySet()) {
            String svcName = entry.getKey();
            Map<String, Object> svcDef = asMap(entry.getValue());
            if (svcDef.isEmpty()) {
                continue;
            }

            String svcId = serviceIds.get(svcName);

            // Properties for the service node
            Map<String, Object> props = new HashMap<>();
            String image = getString(svcDef, "image");
            if (image != null) {
                props.put("image", image);
            }
            Object build = svcDef.get("build");
            if (build instanceof String buildStr) {
                props.put("build_context", buildStr);
            } else if (build instanceof Map<?, ?>) {
                String buildCtx = getString(build, "context");
                if (buildCtx != null) {
                    props.put("build_context", buildCtx);
                }
            }

            // INFRA_RESOURCE node for the service
            CodeNode svcNode = new CodeNode(svcId, NodeKind.INFRA_RESOURCE, svcName);
            svcNode.setFqn("compose:" + svcName);
            svcNode.setModule(ctx.moduleName());
            svcNode.setFilePath(fp);
            svcNode.setProperties(props);
            nodes.add(svcNode);

            // Ports
            List<Object> ports = getList(svcDef, "ports");
            for (Object portEntry : ports) {
                String portStr = String.valueOf(portEntry);
                CodeNode portNode = new CodeNode(
                        "compose:" + fp + ":service:" + svcName + ":port:" + portStr,
                        NodeKind.CONFIG_KEY,
                        svcName + " port " + portStr);
                portNode.setModule(ctx.moduleName());
                portNode.setFilePath(fp);
                portNode.setProperties(Map.of("port", portStr));
                nodes.add(portNode);
            }

            // depends_on
            Object dependsOn = svcDef.get("depends_on");
            if (dependsOn instanceof List<?> depList) {
                for (Object dep : depList) {
                    String depStr = String.valueOf(dep);
                    if (serviceIds.containsKey(depStr)) {
                        edges.add(createEdge(svcId, serviceIds.get(depStr),
                                EdgeKind.DEPENDS_ON, svcName + " depends on " + depStr));
                    }
                }
            } else if (dependsOn instanceof Map<?, ?> depMap) {
                for (Object depKey : depMap.keySet()) {
                    String depStr = String.valueOf(depKey);
                    if (serviceIds.containsKey(depStr)) {
                        edges.add(createEdge(svcId, serviceIds.get(depStr),
                                EdgeKind.DEPENDS_ON, svcName + " depends on " + depStr));
                    }
                }
            }

            // links
            List<Object> links = getList(svcDef, "links");
            for (Object link : links) {
                String linkName = String.valueOf(link).split(":")[0];
                if (serviceIds.containsKey(linkName)) {
                    edges.add(createEdge(svcId, serviceIds.get(linkName),
                            EdgeKind.CONNECTS_TO, svcName + " links to " + linkName));
                }
            }

            // Volumes
            List<Object> volumes = getList(svcDef, "volumes");
            for (Object volEntry : volumes) {
                String volStr;
                if (volEntry instanceof Map<?, ?> volMap) {
                    Object source = ((Map<?, ?>) volMap).get("source");
                    volStr = source != null ? String.valueOf(source) : String.valueOf(volEntry);
                } else {
                    volStr = String.valueOf(volEntry);
                }
                CodeNode volNode = new CodeNode(
                        "compose:" + fp + ":service:" + svcName + ":volume:" + volStr,
                        NodeKind.CONFIG_KEY,
                        svcName + " volume " + volStr);
                volNode.setModule(ctx.moduleName());
                volNode.setFilePath(fp);
                volNode.setProperties(Map.of("volume", volStr));
                nodes.add(volNode);
            }

            // Networks
            Object networks = svcDef.get("networks");
            if (networks instanceof List<?> netList) {
                for (Object net : netList) {
                    String netStr = String.valueOf(net);
                    CodeNode netNode = new CodeNode(
                            "compose:" + fp + ":service:" + svcName + ":network:" + netStr,
                            NodeKind.CONFIG_KEY,
                            svcName + " network " + netStr);
                    netNode.setModule(ctx.moduleName());
                    netNode.setFilePath(fp);
                    netNode.setProperties(Map.of("network", netStr));
                    nodes.add(netNode);
                }
            } else if (networks instanceof Map<?, ?> netMap) {
                for (Object netKey : netMap.keySet()) {
                    String netName = String.valueOf(netKey);
                    CodeNode netNode = new CodeNode(
                            "compose:" + fp + ":service:" + svcName + ":network:" + netName,
                            NodeKind.CONFIG_KEY,
                            svcName + " network " + netName);
                    netNode.setModule(ctx.moduleName());
                    netNode.setFilePath(fp);
                    netNode.setProperties(Map.of("network", netName));
                    nodes.add(netNode);
                }
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private boolean isComposeFile(DetectorContext ctx) {
        String path = ctx.filePath();
        if (path == null) return false;
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String basename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (COMPOSE_FILENAME_RE.matcher(basename).matches()) {
            return true;
        }
        // Fallback: check parsed data for compose-like structure
        Object parsedData = ctx.parsedData();
        if (parsedData instanceof Map<?, ?> pd) {
            if ("yaml".equals(((Map<?, ?>) pd).get("type"))) {
                Object data = ((Map<?, ?>) pd).get("data");
                if (data instanceof Map<?, ?> dataMap && dataMap.containsKey("services")) {
                    return true;
                }
            }
        }
        return false;
    }

    private CodeEdge createEdge(String sourceId, String targetId, EdgeKind kind, String label) {
        CodeEdge edge = new CodeEdge();
        edge.setId(sourceId + "->" + targetId);
        edge.setKind(kind);
        edge.setSourceId(sourceId);
        // Target node reference for edge resolution
        CodeNode targetNode = new CodeNode(targetId, null, null);
        edge.setTarget(targetNode);
        return edge;
    }
}
