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
 * Detects Kubernetes resources, container specs, and cross-resource relationships.
 */
@DetectorInfo(
    name = "kubernetes",
    category = "config",
    description = "Detects Kubernetes resources (Deployments, Services, ConfigMaps, etc.)",
    parser = ParserType.STRUCTURED,
    languages = {"yaml"},
    nodeKinds = {NodeKind.CONFIG_KEY, NodeKind.INFRA_RESOURCE},
    edgeKinds = {EdgeKind.CONNECTS_TO, EdgeKind.DEPENDS_ON},
    properties = {"env_vars", "image", "kind", "namespace", "protocol", "selector"}
)
@Component
public class KubernetesDetector extends AbstractStructuredDetector {
    private static final String PROP_CRONJOB = "CronJob";
    private static final String PROP_DAEMONSET = "DaemonSet";
    private static final String PROP_DEPLOYMENT = "Deployment";
    private static final String PROP_POD = "Pod";
    private static final String PROP_STATEFULSET = "StatefulSet";
    private static final String PROP_DEFAULT = "default";
    private static final String PROP_KIND = "kind";
    private static final String PROP_LABELS = "labels";
    private static final String PROP_METADATA = "metadata";
    private static final String PROP_NAME = "name";
    private static final String PROP_NAMESPACE = "namespace";
    private static final String PROP_SELECTOR = "selector";
    private static final String PROP_SPEC = "spec";


    private static final Set<String> K8S_KINDS = Set.of(
            PROP_DEPLOYMENT, "Service", "ConfigMap", "Secret", "Ingress",
            PROP_POD, PROP_STATEFULSET, PROP_DAEMONSET, "Job", PROP_CRONJOB,
            "Namespace", "PersistentVolumeClaim", "ServiceAccount",
            "Role", "RoleBinding", "ClusterRole", "ClusterRoleBinding");

    private static final Set<String> WORKLOAD_KINDS = Set.of(
            PROP_DEPLOYMENT, PROP_STATEFULSET, PROP_DAEMONSET, "Job", PROP_CRONJOB, PROP_POD);

    private static final Set<String> LABEL_TRACKING_KINDS = Set.of(
            PROP_DEPLOYMENT, PROP_STATEFULSET, PROP_DAEMONSET);

    @Override
    public String getName() {
        return "kubernetes";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("yaml");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        List<Map<String, Object>> documents = getDocuments(ctx);
        if (documents.isEmpty()) {
            return DetectorResult.empty();
        }

        String fp = ctx.filePath();
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Track deployments by match labels for service selector resolution
        Map<String, String> deploymentLabels = new LinkedHashMap<>();
        List<SelectorEntry> serviceSelectors = new ArrayList<>();
        List<IngressBackend> ingressBackends = new ArrayList<>();

        for (Map<String, Object> doc : documents) {
            String kind = safeStr(doc.get(PROP_KIND));
            Map<String, Object> metadata = asMap(doc.get(PROP_METADATA));
            String name = safeStr(metadata.getOrDefault(PROP_NAME, "unknown"));
            String namespace = safeStr(metadata.getOrDefault(PROP_NAMESPACE, PROP_DEFAULT));
            if (namespace.isEmpty()) namespace = PROP_DEFAULT;

            String nodeId = "k8s:" + fp + ":" + kind + ":" + namespace + "/" + name;

            Map<String, Object> props = new HashMap<>();
            props.put(PROP_KIND, kind);
            props.put(PROP_NAMESPACE, namespace);
            Object labels = metadata.get(PROP_LABELS);
            if (labels instanceof Map<?, ?>) {
                props.put(PROP_LABELS, labels);
            }
            Object annotations = metadata.get("annotations");
            if (annotations instanceof Map<?, ?>) {
                props.put("annotations", annotations);
            }

            CodeNode resourceNode = new CodeNode(nodeId, NodeKind.INFRA_RESOURCE,
                    kind + "/" + name);
            resourceNode.setFqn("k8s:" + kind + ":" + namespace + "/" + name);
            resourceNode.setModule(ctx.moduleName());
            resourceNode.setFilePath(fp);
            resourceNode.setProperties(props);
            nodes.add(resourceNode);

            Map<String, Object> spec = asMap(doc.get(PROP_SPEC));

            // Extract container specs from workload resources
            if (WORKLOAD_KINDS.contains(kind)) {
                List<Map<String, Object>> containers = extractContainers(spec, kind);
                for (Map<String, Object> container : containers) {
                    String cName = safeStr(container.getOrDefault(PROP_NAME, "unnamed"));
                    Map<String, Object> cProps = new HashMap<>();

                    String image = getString(container, "image");
                    if (image != null) {
                        cProps.put("image", image);
                    }

                    List<Object> cPorts = getList(container, "ports");
                    if (!cPorts.isEmpty()) {
                        List<String> portStrs = new ArrayList<>();
                        for (Object p : cPorts) {
                            Map<String, Object> pm = asMap(p);
                            if (!pm.isEmpty()) {
                                portStrs.add(pm.getOrDefault("containerPort", "?") + "/"
                                        + pm.getOrDefault("protocol", "TCP"));
                            }
                        }
                        if (!portStrs.isEmpty()) {
                            cProps.put("ports", portStrs);
                        }
                    }

                    List<Object> envVars = getList(container, "env");
                    if (!envVars.isEmpty()) {
                        List<String> envNames = new ArrayList<>();
                        for (Object e : envVars) {
                            Map<String, Object> em = asMap(e);
                            String envName = getString(em, PROP_NAME);
                            if (envName != null) {
                                envNames.add(envName);
                            }
                        }
                        if (!envNames.isEmpty()) {
                            cProps.put("env_vars", envNames);
                        }
                    }

                    CodeNode containerNode = new CodeNode(nodeId + ":container:" + cName,
                            NodeKind.CONFIG_KEY, name + "/" + cName);
                    containerNode.setModule(ctx.moduleName());
                    containerNode.setFilePath(fp);
                    containerNode.setProperties(cProps);
                    nodes.add(containerNode);
                }
            }

            // Track deployment match labels
            if (LABEL_TRACKING_KINDS.contains(kind)) {
                Map<String, Object> template = getMap(spec, "template");
                Map<String, Object> tmplMeta = getMap(template, PROP_METADATA);
                Map<String, Object> tmplLabels = getMap(tmplMeta, PROP_LABELS);
                for (var le : tmplLabels.entrySet()) {
                    deploymentLabels.put(le.getKey() + "=" + le.getValue(), nodeId);
                }

                Map<String, Object> selector = getMap(spec, PROP_SELECTOR);
                Map<String, Object> matchLabels = getMap(selector, "matchLabels");
                for (var le : matchLabels.entrySet()) {
                    deploymentLabels.put(le.getKey() + "=" + le.getValue(), nodeId);
                }
            }

            // Track service selectors
            if ("Service".equals(kind)) {
                Map<String, Object> svcSelector = getMap(spec, PROP_SELECTOR);
                if (!svcSelector.isEmpty()) {
                    serviceSelectors.add(new SelectorEntry(nodeId, svcSelector));
                }
            }

            // Track ingress backends
            if ("Ingress".equals(kind)) {
                collectIngressBackends(spec, nodeId, ingressBackends);
            }
        }

        // Resolve service selector -> deployment edges
        for (SelectorEntry se : serviceSelectors) {
            for (var selEntry : se.selector.entrySet()) {
                String labelTag = selEntry.getKey() + "=" + selEntry.getValue();
                String targetId = deploymentLabels.get(labelTag);
                if (targetId != null) {
                    edges.add(createEdge(se.nodeId, targetId, EdgeKind.DEPENDS_ON,
                            "service selects " + labelTag, Map.of(PROP_SELECTOR, labelTag)));
                }
            }
        }

        // Resolve ingress -> service edges
        Map<String, String> serviceNameToId = new LinkedHashMap<>();
        for (Map<String, Object> doc : documents) {
            if (!"Service".equals(doc.get(PROP_KIND))) continue;
            Map<String, Object> meta = asMap(doc.get(PROP_METADATA));
            String svcName = safeStr(meta.getOrDefault(PROP_NAME, ""));
            String ns = safeStr(meta.getOrDefault(PROP_NAMESPACE, PROP_DEFAULT));
            if (ns.isEmpty()) ns = PROP_DEFAULT;
            serviceNameToId.put(svcName, "k8s:" + fp + ":Service:" + ns + "/" + svcName);
        }

        for (IngressBackend ib : ingressBackends) {
            String targetId = serviceNameToId.get(ib.serviceName);
            if (targetId != null) {
                edges.add(createEdge(ib.ingressNodeId, targetId, EdgeKind.CONNECTS_TO,
                        "ingress routes to " + ib.serviceName, Map.of()));
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private List<Map<String, Object>> getDocuments(DetectorContext ctx) {
        Object parsedData = ctx.parsedData();
        if (parsedData == null) return List.of();

        Map<String, Object> pd = asMap(parsedData);
        String ptype = getString(pd, "type");

        if ("yaml_multi".equals(ptype)) {
            List<Object> docs = getList(pd, "documents");
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object d : docs) {
                Map<String, Object> doc = asMap(d);
                String docKind = getString(doc, PROP_KIND);
                if (docKind != null && K8S_KINDS.contains(docKind)) {
                    result.add(doc);
                }
            }
            return result;
        }

        if ("yaml".equals(ptype)) {
            Map<String, Object> data = getMap(pd, "data");
            String dataKind = getString(data, PROP_KIND);
            if (dataKind != null && K8S_KINDS.contains(dataKind)) {
                return List.of(data);
            }
        }

        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractContainers(Map<String, Object> spec, String kind) {
        List<Map<String, Object>> containers = new ArrayList<>();

        if ("Pod".equals(kind)) {
            List<Object> cs = getList(spec, "containers");
            for (Object c : cs) {
                Map<String, Object> cm = asMap(c);
                if (!cm.isEmpty()) containers.add(cm);
            }
            return containers;
        }

        Map<String, Object> workSpec = spec;
        if ("CronJob".equals(kind)) {
            Map<String, Object> jobTemplate = getMap(spec, "jobTemplate");
            workSpec = getMap(jobTemplate, PROP_SPEC);
            if (workSpec.isEmpty()) return containers;
        }

        Map<String, Object> template = getMap(workSpec, "template");
        Map<String, Object> podSpec = getMap(template, PROP_SPEC);

        List<Object> cs = getList(podSpec, "containers");
        for (Object c : cs) {
            Map<String, Object> cm = asMap(c);
            if (!cm.isEmpty()) containers.add(cm);
        }
        List<Object> initCs = getList(podSpec, "initContainers");
        for (Object c : initCs) {
            Map<String, Object> cm = asMap(c);
            if (!cm.isEmpty()) containers.add(cm);
        }

        return containers;
    }

    private void collectIngressBackends(Map<String, Object> spec, String ingressNodeId,
                                         List<IngressBackend> out) {
        // Default backend
        Map<String, Object> defaultBackend = getMap(spec, "defaultBackend");
        if (defaultBackend.isEmpty()) {
            defaultBackend = getMap(spec, "backend");
        }
        if (!defaultBackend.isEmpty()) {
            Map<String, Object> svc = getMap(defaultBackend, "service");
            if (svc.isEmpty()) svc = defaultBackend;
            String svcName = getString(svc, PROP_NAME);
            if (svcName == null) svcName = getString(svc, "serviceName");
            if (svcName != null) {
                out.add(new IngressBackend(ingressNodeId, svcName));
            }
        }

        // Rules
        List<Object> rules = getList(spec, "rules");
        for (Object rule : rules) {
            Map<String, Object> ruleMap = asMap(rule);
            Map<String, Object> http = getMap(ruleMap, "http");
            List<Object> paths = getList(http, "paths");
            for (Object pathEntry : paths) {
                Map<String, Object> pe = asMap(pathEntry);
                Map<String, Object> backend = getMap(pe, "backend");
                if (backend.isEmpty()) continue;
                Map<String, Object> svc = getMap(backend, "service");
                if (svc.isEmpty()) svc = backend;
                String svcName = getString(svc, PROP_NAME);
                if (svcName == null) svcName = getString(svc, "serviceName");
                if (svcName != null) {
                    out.add(new IngressBackend(ingressNodeId, svcName));
                }
            }
        }
    }

    private static String safeStr(Object val) {
        return val == null ? "" : String.valueOf(val);
    }

    private CodeEdge createEdge(String sourceId, String targetId, EdgeKind kind,
                                 String label, Map<String, Object> props) {
        CodeEdge edge = new CodeEdge();
        edge.setId(sourceId + "->" + targetId);
        edge.setKind(kind);
        edge.setSourceId(sourceId);
        edge.setTarget(new CodeNode(targetId, null, null));
        edge.setProperties(new HashMap<>(props));
        return edge;
    }

    private record SelectorEntry(String nodeId, Map<String, Object> selector) {}
    private record IngressBackend(String ingressNodeId, String serviceName) {}
}
