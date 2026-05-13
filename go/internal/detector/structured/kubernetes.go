package structured

import (
	"fmt"
	"sort"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// KubernetesDetector mirrors Java KubernetesDetector. Emits INFRA_RESOURCE
// nodes per k8s manifest (Deployment/Service/Ingress/Pod/...). Workload
// resources get a CONFIG_KEY child per container. Resolves
// service-selector → deployment edges and ingress-backend → service edges.
type KubernetesDetector struct{}

func NewKubernetesDetector() *KubernetesDetector { return &KubernetesDetector{} }

func (KubernetesDetector) Name() string                        { return "kubernetes" }
func (KubernetesDetector) SupportedLanguages() []string        { return []string{"yaml"} }
func (KubernetesDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewKubernetesDetector()) }

var k8sKinds = map[string]bool{
	"Deployment": true, "Service": true, "ConfigMap": true, "Secret": true,
	"Ingress": true, "Pod": true, "StatefulSet": true, "DaemonSet": true,
	"Job": true, "CronJob": true, "Namespace": true, "PersistentVolumeClaim": true,
	"ServiceAccount": true, "Role": true, "RoleBinding": true,
	"ClusterRole": true, "ClusterRoleBinding": true,
}

var k8sWorkloadKinds = map[string]bool{
	"Deployment": true, "StatefulSet": true, "DaemonSet": true,
	"Job": true, "CronJob": true, "Pod": true,
}

var k8sLabelTrackingKinds = map[string]bool{
	"Deployment": true, "StatefulSet": true, "DaemonSet": true,
}

type selectorEntry struct {
	nodeID   string
	selector map[string]any
}

type ingressBackend struct {
	ingressNodeID string
	serviceName   string
}

func (d KubernetesDetector) Detect(ctx *detector.Context) *detector.Result {
	documents := d.getDocuments(ctx)
	if len(documents) == 0 {
		return detector.EmptyResult()
	}

	fp := ctx.FilePath
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	// Use ordered slices so that determinism survives. Use insertion order
	// of documents which is deterministic from the parser.
	type lblEntry struct {
		label  string
		nodeID string
	}
	var deploymentLabels []lblEntry
	var serviceSelectors []selectorEntry
	var ingressBackends []ingressBackend

	for _, doc := range documents {
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
		nodeID := "k8s:" + fp + ":" + kind + ":" + namespace + "/" + name
		n := model.NewCodeNode(nodeID, model.NodeInfraResource, kind+"/"+name)
		n.FQN = "k8s:" + kind + ":" + namespace + "/" + name
		n.Module = ctx.ModuleName
		n.FilePath = fp
		n.Confidence = base.StructuredDetectorDefaultConfidence
		n.Properties["kind"] = kind
		n.Properties["namespace"] = namespace
		if lbl := base.AsMap(metadata["labels"]); lbl != nil {
			n.Properties["labels"] = lbl
		}
		if ann := base.AsMap(metadata["annotations"]); ann != nil {
			n.Properties["annotations"] = ann
		}
		nodes = append(nodes, n)

		spec := base.AsMap(doc["spec"])

		if k8sWorkloadKinds[kind] {
			containers := extractContainers(spec, kind)
			for _, c := range containers {
				cName := safeString(c["name"])
				if cName == "" {
					cName = "unnamed"
				}
				cn := model.NewCodeNode(nodeID+":container:"+cName,
					model.NodeConfigKey, name+"/"+cName)
				cn.Module = ctx.ModuleName
				cn.FilePath = fp
				cn.Confidence = base.StructuredDetectorDefaultConfidence
				if img := base.GetString(c, "image"); img != "" {
					cn.Properties["image"] = img
				}
				// ports — collect to "containerPort/protocol" strings
				if ports := base.GetList(c, "ports"); len(ports) > 0 {
					var pStrs []string
					for _, p := range ports {
						pm := base.AsMap(p)
						if pm == nil {
							continue
						}
						portVal := "?"
						if v, ok := pm["containerPort"]; ok {
							portVal = safeString(v)
						}
						proto := safeString(pm["protocol"])
						if proto == "" {
							proto = "TCP"
						}
						pStrs = append(pStrs, portVal+"/"+proto)
					}
					if len(pStrs) > 0 {
						cn.Properties["ports"] = pStrs
					}
				}
				if envs := base.GetList(c, "env"); len(envs) > 0 {
					var envNames []string
					for _, e := range envs {
						em := base.AsMap(e)
						if em == nil {
							continue
						}
						if envN := base.GetString(em, "name"); envN != "" {
							envNames = append(envNames, envN)
						}
					}
					if len(envNames) > 0 {
						cn.Properties["env_vars"] = envNames
					}
				}
				nodes = append(nodes, cn)
			}
		}

		if k8sLabelTrackingKinds[kind] {
			template := base.GetMap(spec, "template")
			tmplMeta := base.GetMap(template, "metadata")
			tmplLabels := base.GetMap(tmplMeta, "labels")
			tlKeys := mapKeysSorted(tmplLabels)
			for _, k := range tlKeys {
				deploymentLabels = append(deploymentLabels, lblEntry{
					label: k + "=" + safeString(tmplLabels[k]), nodeID: nodeID})
			}
			selector := base.GetMap(spec, "selector")
			ml := base.GetMap(selector, "matchLabels")
			mlKeys := mapKeysSorted(ml)
			for _, k := range mlKeys {
				deploymentLabels = append(deploymentLabels, lblEntry{
					label: k + "=" + safeString(ml[k]), nodeID: nodeID})
			}
		}

		if kind == "Service" {
			sel := base.GetMap(spec, "selector")
			if len(sel) > 0 {
				serviceSelectors = append(serviceSelectors, selectorEntry{nodeID: nodeID, selector: sel})
			}
		}

		if kind == "Ingress" {
			collectIngressBackends(spec, nodeID, &ingressBackends)
		}
	}

	// Build deploymentLabels lookup (first-write-wins per label).
	labelToDeploy := map[string]string{}
	for _, le := range deploymentLabels {
		if _, ok := labelToDeploy[le.label]; !ok {
			labelToDeploy[le.label] = le.nodeID
		}
	}

	// Service → Deployment edges via selector.
	for _, se := range serviceSelectors {
		selKeys := mapKeysSorted(se.selector)
		for _, k := range selKeys {
			tag := k + "=" + safeString(se.selector[k])
			if target, ok := labelToDeploy[tag]; ok {
				e := model.NewCodeEdge(se.nodeID+"->"+target, model.EdgeDependsOn, se.nodeID, target)
				e.Confidence = base.StructuredDetectorDefaultConfidence
				e.Properties["selector"] = tag
				edges = append(edges, e)
			}
		}
	}

	// Ingress → Service edges by service name.
	serviceNameToID := map[string]string{}
	for _, doc := range documents {
		if safeString(doc["kind"]) != "Service" {
			continue
		}
		meta := base.AsMap(doc["metadata"])
		svcName := safeString(meta["name"])
		ns := safeString(meta["namespace"])
		if ns == "" {
			ns = "default"
		}
		serviceNameToID[svcName] = "k8s:" + fp + ":Service:" + ns + "/" + svcName
	}
	for _, ib := range ingressBackends {
		if target, ok := serviceNameToID[ib.serviceName]; ok {
			e := model.NewCodeEdge(ib.ingressNodeID+"->"+target, model.EdgeConnectsTo,
				ib.ingressNodeID, target)
			e.Confidence = base.StructuredDetectorDefaultConfidence
			edges = append(edges, e)
		}
	}

	return detector.ResultOf(nodes, edges)
}

func (d KubernetesDetector) getDocuments(ctx *detector.Context) []map[string]any {
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
			if kind != "" && k8sKinds[kind] {
				out = append(out, m)
			}
		}
		return out
	case "yaml":
		data := base.GetMap(ctx.ParsedData, "data")
		kind := base.GetString(data, "kind")
		if kind != "" && k8sKinds[kind] {
			return []map[string]any{data}
		}
	}
	return nil
}

func extractContainers(spec map[string]any, kind string) []map[string]any {
	var containers []map[string]any
	if kind == "Pod" {
		for _, c := range base.GetList(spec, "containers") {
			cm := base.AsMap(c)
			if cm != nil {
				containers = append(containers, cm)
			}
		}
		return containers
	}
	workSpec := spec
	if kind == "CronJob" {
		jobTpl := base.GetMap(spec, "jobTemplate")
		workSpec = base.GetMap(jobTpl, "spec")
		if workSpec == nil {
			return containers
		}
	}
	tpl := base.GetMap(workSpec, "template")
	podSpec := base.GetMap(tpl, "spec")
	for _, c := range base.GetList(podSpec, "containers") {
		cm := base.AsMap(c)
		if cm != nil {
			containers = append(containers, cm)
		}
	}
	for _, c := range base.GetList(podSpec, "initContainers") {
		cm := base.AsMap(c)
		if cm != nil {
			containers = append(containers, cm)
		}
	}
	return containers
}

func collectIngressBackends(spec map[string]any, ingressNodeID string, out *[]ingressBackend) {
	defaultBackend := base.GetMap(spec, "defaultBackend")
	if defaultBackend == nil {
		defaultBackend = base.GetMap(spec, "backend")
	}
	if defaultBackend != nil {
		svc := base.GetMap(defaultBackend, "service")
		if svc == nil {
			svc = defaultBackend
		}
		svcName := base.GetString(svc, "name")
		if svcName == "" {
			svcName = base.GetString(svc, "serviceName")
		}
		if svcName != "" {
			*out = append(*out, ingressBackend{ingressNodeID: ingressNodeID, serviceName: svcName})
		}
	}
	for _, rule := range base.GetList(spec, "rules") {
		rm := base.AsMap(rule)
		http := base.GetMap(rm, "http")
		for _, pe := range base.GetList(http, "paths") {
			pm := base.AsMap(pe)
			backend := base.GetMap(pm, "backend")
			if backend == nil {
				continue
			}
			svc := base.GetMap(backend, "service")
			if svc == nil {
				svc = backend
			}
			svcName := base.GetString(svc, "name")
			if svcName == "" {
				svcName = base.GetString(svc, "serviceName")
			}
			if svcName != "" {
				*out = append(*out, ingressBackend{ingressNodeID: ingressNodeID, serviceName: svcName})
			}
		}
	}
}

// safeString coerces an any to its string representation (empty string for
// nil values). Numbers / booleans use fmt.Sprint semantics.
func safeString(v any) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprint(v)
}

func mapKeysSorted(m map[string]any) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}
