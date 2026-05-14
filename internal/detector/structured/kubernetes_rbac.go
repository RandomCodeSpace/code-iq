package structured

import (
	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// KubernetesRbacDetector mirrors Java KubernetesRbacDetector. Emits GUARD
// nodes for Role / ClusterRole / RoleBinding / ClusterRoleBinding /
// ServiceAccount, and PROTECTS edges from each Role(Binding) to its bound
// ServiceAccount subjects.
type KubernetesRbacDetector struct{}

func NewKubernetesRbacDetector() *KubernetesRbacDetector { return &KubernetesRbacDetector{} }

func (KubernetesRbacDetector) Name() string                        { return "config.kubernetes_rbac" }
func (KubernetesRbacDetector) SupportedLanguages() []string        { return []string{"yaml"} }
func (KubernetesRbacDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewKubernetesRbacDetector()) }

var rbacKinds = map[string]bool{
	"Role": true, "ClusterRole": true,
	"RoleBinding": true, "ClusterRoleBinding": true,
	"ServiceAccount": true,
}

func (d KubernetesRbacDetector) Detect(ctx *detector.Context) *detector.Result {
	docs := getRbacDocuments(ctx)
	if len(docs) == 0 {
		return detector.EmptyResult()
	}
	fp := ctx.FilePath
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	// Insertion-order tracking for determinism (documents arrive deterministically).
	type kv struct {
		key, value string
	}
	var roleNodes []kv
	var saNodes []kv
	var bindings []map[string]any

	for _, doc := range docs {
		kind := safeString(doc["kind"])
		metadata := base.AsMap(doc["metadata"])
		name := safeString(metadata["name"])
		if name == "" {
			name = "unknown"
		}
		namespace := safeString(metadata["namespace"])
		if namespace == "" {
			namespace = "default"
		}
		nodeID := "k8s_rbac:" + fp + ":" + kind + ":" + namespace + "/" + name

		switch kind {
		case "Role", "ClusterRole":
			var serialized []map[string]any
			for _, rule := range base.GetList(doc, "rules") {
				rm := base.AsMap(rule)
				if len(rm) == 0 {
					continue
				}
				sr := map[string]any{
					"apiGroups": defaultEmptyList(rm["apiGroups"]),
					"resources": defaultEmptyList(rm["resources"]),
					"verbs":     defaultEmptyList(rm["verbs"]),
				}
				serialized = append(serialized, sr)
			}
			n := model.NewCodeNode(nodeID, model.NodeGuard, kind+"/"+name)
			n.FQN = "k8s:" + kind + ":" + namespace + "/" + name
			n.Module = ctx.ModuleName
			n.FilePath = fp
			n.Confidence = base.StructuredDetectorDefaultConfidence
			n.Properties["auth_type"] = "k8s_rbac"
			n.Properties["k8s_kind"] = kind
			n.Properties["namespace"] = namespace
			n.Properties["rules"] = serialized
			nodes = append(nodes, n)

			var roleKey string
			if kind == "ClusterRole" {
				roleKey = "ClusterRole:cluster-wide/" + name
			} else {
				roleKey = kind + ":" + namespace + "/" + name
			}
			roleNodes = append(roleNodes, kv{roleKey, nodeID})

		case "ServiceAccount":
			n := model.NewCodeNode(nodeID, model.NodeGuard, "ServiceAccount/"+name)
			n.FQN = "k8s:ServiceAccount:" + namespace + "/" + name
			n.Module = ctx.ModuleName
			n.FilePath = fp
			n.Confidence = base.StructuredDetectorDefaultConfidence
			n.Properties["auth_type"] = "k8s_rbac"
			n.Properties["k8s_kind"] = "ServiceAccount"
			n.Properties["namespace"] = namespace
			n.Properties["rules"] = []map[string]any{}
			nodes = append(nodes, n)
			saNodes = append(saNodes, kv{namespace + "/" + name, nodeID})

		case "RoleBinding", "ClusterRoleBinding":
			n := model.NewCodeNode(nodeID, model.NodeGuard, kind+"/"+name)
			n.FQN = "k8s:" + kind + ":" + namespace + "/" + name
			n.Module = ctx.ModuleName
			n.FilePath = fp
			n.Confidence = base.StructuredDetectorDefaultConfidence
			n.Properties["auth_type"] = "k8s_rbac"
			n.Properties["k8s_kind"] = kind
			n.Properties["namespace"] = namespace
			n.Properties["rules"] = []map[string]any{}
			nodes = append(nodes, n)
			bindings = append(bindings, doc)
		}
	}

	// Build role lookup.
	roleLookup := map[string]string{}
	for _, r := range roleNodes {
		if _, ok := roleLookup[r.key]; !ok {
			roleLookup[r.key] = r.value
		}
	}
	saLookup := map[string]string{}
	for _, s := range saNodes {
		if _, ok := saLookup[s.key]; !ok {
			saLookup[s.key] = s.value
		}
	}

	for _, doc := range bindings {
		kind := safeString(doc["kind"])
		metadata := base.AsMap(doc["metadata"])
		bindingNs := safeString(metadata["namespace"])
		if bindingNs == "" {
			bindingNs = "default"
		}
		roleRef := base.GetMap(doc, "roleRef")
		if len(roleRef) == 0 {
			continue
		}
		refKind := safeString(roleRef["kind"])
		refName := safeString(roleRef["name"])
		var roleKey string
		if refKind == "ClusterRole" {
			roleKey = "ClusterRole:cluster-wide/" + refName
		} else {
			roleKey = refKind + ":" + bindingNs + "/" + refName
		}
		roleNid, ok := roleLookup[roleKey]
		if !ok {
			continue
		}
		for _, subject := range base.GetList(doc, "subjects") {
			subj := base.AsMap(subject)
			if len(subj) == 0 {
				continue
			}
			subjKind := safeString(subj["kind"])
			subjName := safeString(subj["name"])
			subjNs := safeString(subj["namespace"])
			if subjNs == "" {
				subjNs = bindingNs
			}
			if subjKind != "ServiceAccount" {
				continue
			}
			saKey := subjNs + "/" + subjName
			saNid, ok := saLookup[saKey]
			if !ok {
				continue
			}
			e := model.NewCodeEdge(roleNid+"->"+saNid, model.EdgeProtects, roleNid, saNid)
			e.Confidence = base.StructuredDetectorDefaultConfidence
			e.Properties["binding_kind"] = kind
			edges = append(edges, e)
		}
	}
	return detector.ResultOf(nodes, edges)
}

func getRbacDocuments(ctx *detector.Context) []map[string]any {
	if ctx.ParsedData == nil {
		return nil
	}
	ptype := base.GetString(ctx.ParsedData, "type")
	switch ptype {
	case "yaml_multi":
		var out []map[string]any
		for _, doc := range base.GetList(ctx.ParsedData, "documents") {
			m := base.AsMap(doc)
			kind := base.GetString(m, "kind")
			if kind != "" && rbacKinds[kind] {
				out = append(out, m)
			}
		}
		return out
	case "yaml":
		data := base.GetMap(ctx.ParsedData, "data")
		kind := base.GetString(data, "kind")
		if kind != "" && rbacKinds[kind] {
			return []map[string]any{data}
		}
	}
	return nil
}

func defaultEmptyList(v any) []any {
	if v == nil {
		return []any{}
	}
	if l, ok := v.([]any); ok {
		return l
	}
	return []any{}
}
