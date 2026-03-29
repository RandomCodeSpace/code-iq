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

/**
 * Detects Kubernetes RBAC resources and produces GUARD nodes and PROTECTS edges.
 */
@Component
public class KubernetesRbacDetector extends AbstractStructuredDetector {

    private static final Set<String> RBAC_KINDS = Set.of(
            "Role", "ClusterRole", "RoleBinding", "ClusterRoleBinding", "ServiceAccount");

    @Override
    public String getName() {
        return "config.kubernetes_rbac";
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

        Map<String, String> roleNodes = new LinkedHashMap<>();
        Map<String, String> saNodes = new LinkedHashMap<>();
        List<Map<String, Object>> bindings = new ArrayList<>();

        for (Map<String, Object> doc : documents) {
            String kind = safeStr(doc.get("kind"));
            Map<String, Object> metadata = asMap(doc.get("metadata"));
            String name = safeStr(metadata.getOrDefault("name", "unknown"));
            String namespace = safeStr(metadata.getOrDefault("namespace", "default"));
            if (namespace.isEmpty()) namespace = "default";

            String nodeId = "k8s_rbac:" + fp + ":" + kind + ":" + namespace + "/" + name;

            if ("Role".equals(kind) || "ClusterRole".equals(kind)) {
                List<Object> rules = getList(doc, "rules");
                List<Map<String, Object>> serializedRules = new ArrayList<>();
                for (Object rule : rules) {
                    Map<String, Object> rm = asMap(rule);
                    if (!rm.isEmpty()) {
                        Map<String, Object> sr = new LinkedHashMap<>();
                        sr.put("apiGroups", rm.getOrDefault("apiGroups", List.of()));
                        sr.put("resources", rm.getOrDefault("resources", List.of()));
                        sr.put("verbs", rm.getOrDefault("verbs", List.of()));
                        serializedRules.add(sr);
                    }
                }

                Map<String, Object> props = new LinkedHashMap<>();
                props.put("auth_type", "k8s_rbac");
                props.put("k8s_kind", kind);
                props.put("namespace", namespace);
                props.put("rules", serializedRules);

                CodeNode node = new CodeNode(nodeId, NodeKind.GUARD, kind + "/" + name);
                node.setFqn("k8s:" + kind + ":" + namespace + "/" + name);
                node.setModule(ctx.moduleName());
                node.setFilePath(fp);
                node.setProperties(props);
                nodes.add(node);

                String roleKey = "ClusterRole".equals(kind)
                        ? "ClusterRole:cluster-wide/" + name
                        : kind + ":" + namespace + "/" + name;
                roleNodes.put(roleKey, nodeId);

            } else if ("ServiceAccount".equals(kind)) {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("auth_type", "k8s_rbac");
                props.put("k8s_kind", "ServiceAccount");
                props.put("namespace", namespace);
                props.put("rules", List.of());

                CodeNode node = new CodeNode(nodeId, NodeKind.GUARD,
                        "ServiceAccount/" + name);
                node.setFqn("k8s:ServiceAccount:" + namespace + "/" + name);
                node.setModule(ctx.moduleName());
                node.setFilePath(fp);
                node.setProperties(props);
                nodes.add(node);

                saNodes.put(namespace + "/" + name, nodeId);

            } else if ("RoleBinding".equals(kind) || "ClusterRoleBinding".equals(kind)) {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("auth_type", "k8s_rbac");
                props.put("k8s_kind", kind);
                props.put("namespace", namespace);
                props.put("rules", List.of());

                CodeNode node = new CodeNode(nodeId, NodeKind.GUARD, kind + "/" + name);
                node.setFqn("k8s:" + kind + ":" + namespace + "/" + name);
                node.setModule(ctx.moduleName());
                node.setFilePath(fp);
                node.setProperties(props);
                nodes.add(node);

                bindings.add(doc);
            }
        }

        // Resolve RoleBinding/ClusterRoleBinding -> PROTECTS edges
        for (Map<String, Object> doc : bindings) {
            String kind = safeStr(doc.get("kind"));
            Map<String, Object> metadata = asMap(doc.get("metadata"));
            String bindingNamespace = safeStr(metadata.getOrDefault("namespace", "default"));
            if (bindingNamespace.isEmpty()) bindingNamespace = "default";

            Map<String, Object> roleRef = getMap(doc, "roleRef");
            if (roleRef.isEmpty()) continue;

            String refKind = safeStr(roleRef.get("kind"));
            String refName = safeStr(roleRef.get("name"));

            String roleKey = "ClusterRole".equals(refKind)
                    ? "ClusterRole:cluster-wide/" + refName
                    : refKind + ":" + bindingNamespace + "/" + refName;

            String roleNid = roleNodes.get(roleKey);
            if (roleNid == null) continue;

            List<Object> subjects = getList(doc, "subjects");
            for (Object subject : subjects) {
                Map<String, Object> subj = asMap(subject);
                if (subj.isEmpty()) continue;

                String subjKind = safeStr(subj.get("kind"));
                String subjName = safeStr(subj.get("name"));
                String subjNamespace = safeStr(subj.getOrDefault("namespace", bindingNamespace));
                if (subjNamespace.isEmpty()) subjNamespace = bindingNamespace;

                if ("ServiceAccount".equals(subjKind)) {
                    String saKey = subjNamespace + "/" + subjName;
                    String saNid = saNodes.get(saKey);
                    if (saNid != null) {
                        CodeEdge edge = new CodeEdge();
                        edge.setId(roleNid + "->" + saNid);
                        edge.setKind(EdgeKind.PROTECTS);
                        edge.setSourceId(roleNid);
                        edge.setTarget(new CodeNode(saNid, null, null));
                        edge.setProperties(Map.of("binding_kind", kind));
                        edges.add(edge);
                    }
                }
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
                String docKind = getString(doc, "kind");
                if (docKind != null && RBAC_KINDS.contains(docKind)) {
                    result.add(doc);
                }
            }
            return result;
        }

        if ("yaml".equals(ptype)) {
            Map<String, Object> data = getMap(pd, "data");
            String dataKind = getString(data, "kind");
            if (dataKind != null && RBAC_KINDS.contains(dataKind)) {
                return List.of(data);
            }
        }

        return List.of();
    }

    private static String safeStr(Object val) {
        return val == null ? "" : String.valueOf(val);
    }
}
