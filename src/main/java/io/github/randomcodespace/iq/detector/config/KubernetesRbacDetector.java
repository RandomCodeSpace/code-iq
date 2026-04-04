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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

/**
 * Detects Kubernetes RBAC resources and produces GUARD nodes and PROTECTS edges.
 */
@DetectorInfo(
    name = "config.kubernetes_rbac",
    category = "config",
    description = "Detects Kubernetes RBAC resources (Roles, RoleBindings, ServiceAccounts)",
    parser = ParserType.STRUCTURED,
    languages = {"yaml"},
    nodeKinds = {NodeKind.GUARD},
    edgeKinds = {EdgeKind.PROTECTS},
    properties = {"auth_type", "kind", "namespace"}
)
@Component
public class KubernetesRbacDetector extends AbstractStructuredDetector {
    private static final String PROP_CLUSTERROLE = "ClusterRole";
    private static final String PROP_SERVICEACCOUNT = "ServiceAccount";
    private static final String PROP_AUTH_TYPE = "auth_type";
    private static final String PROP_DEFAULT = "default";
    private static final String PROP_K8S_KIND = "k8s_kind";
    private static final String PROP_K8S_RBAC = "k8s_rbac";
    private static final String PROP_KIND = "kind";
    private static final String PROP_NAME = "name";
    private static final String PROP_NAMESPACE = "namespace";
    private static final String PROP_RULES = "rules";


    private static final Set<String> RBAC_KINDS = Set.of(
            "Role", PROP_CLUSTERROLE, "RoleBinding", "ClusterRoleBinding", PROP_SERVICEACCOUNT);

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
            String kind = safeStr(doc.get(PROP_KIND));
            Map<String, Object> metadata = asMap(doc.get("metadata"));
            String name = safeStr(metadata.getOrDefault(PROP_NAME, "unknown"));
            String namespace = safeStr(metadata.getOrDefault(PROP_NAMESPACE, PROP_DEFAULT));
            if (namespace.isEmpty()) namespace = PROP_DEFAULT;

            String nodeId = "k8s_rbac:" + fp + ":" + kind + ":" + namespace + "/" + name;

            if ("Role".equals(kind) || "ClusterRole".equals(kind)) {
                List<Object> rules = getList(doc, PROP_RULES);
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
                props.put(PROP_AUTH_TYPE, PROP_K8S_RBAC);
                props.put(PROP_K8S_KIND, kind);
                props.put(PROP_NAMESPACE, namespace);
                props.put(PROP_RULES, serializedRules);

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
                props.put(PROP_AUTH_TYPE, PROP_K8S_RBAC);
                props.put(PROP_K8S_KIND, PROP_SERVICEACCOUNT);
                props.put(PROP_NAMESPACE, namespace);
                props.put(PROP_RULES, List.of());

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
                props.put(PROP_AUTH_TYPE, PROP_K8S_RBAC);
                props.put(PROP_K8S_KIND, kind);
                props.put(PROP_NAMESPACE, namespace);
                props.put(PROP_RULES, List.of());

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
            String kind = safeStr(doc.get(PROP_KIND));
            Map<String, Object> metadata = asMap(doc.get("metadata"));
            String bindingNamespace = safeStr(metadata.getOrDefault(PROP_NAMESPACE, PROP_DEFAULT));
            if (bindingNamespace.isEmpty()) bindingNamespace = PROP_DEFAULT;

            Map<String, Object> roleRef = getMap(doc, "roleRef");
            if (roleRef.isEmpty()) continue;

            String refKind = safeStr(roleRef.get(PROP_KIND));
            String refName = safeStr(roleRef.get(PROP_NAME));

            String roleKey = "ClusterRole".equals(refKind)
                    ? "ClusterRole:cluster-wide/" + refName
                    : refKind + ":" + bindingNamespace + "/" + refName;

            String roleNid = roleNodes.get(roleKey);
            if (roleNid == null) continue;

            List<Object> subjects = getList(doc, "subjects");
            for (Object subject : subjects) {
                Map<String, Object> subj = asMap(subject);
                if (subj.isEmpty()) continue;

                String subjKind = safeStr(subj.get(PROP_KIND));
                String subjName = safeStr(subj.get(PROP_NAME));
                String subjNamespace = safeStr(subj.getOrDefault(PROP_NAMESPACE, bindingNamespace));
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
                String docKind = getString(doc, PROP_KIND);
                if (docKind != null && RBAC_KINDS.contains(docKind)) {
                    result.add(doc);
                }
            }
            return result;
        }

        if ("yaml".equals(ptype)) {
            Map<String, Object> data = getMap(pd, "data");
            String dataKind = getString(data, PROP_KIND);
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
