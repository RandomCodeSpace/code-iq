package flow

import (
	"fmt"
	"math"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// View builders — one per supported view. Mirrors
// src/main/java/.../flow/FlowViews.java exactly (same subgraph IDs, same
// node IDs, same labels) so a Java vs Go diff on the same fixture matches
// 1:1 modulo language-specific formatting differences.

// Property key constants (mirror Java side `PROP_*` constants).
const (
	keyCount = "count"
)

// isCINode mirrors Java FlowViews.isCiNode — a node ID containing "gha:"
// or "gitlab:" is treated as a CI node and excluded from the application
// subgraphs.
func isCINode(id string) bool {
	return strings.Contains(id, "gha:") || strings.Contains(id, "gitlab:")
}

// containsInfra reports whether the node's ID contains the supplied
// substring (case-insensitive for "dockerfile" matches).
func containsInfra(id, needle string) bool {
	return strings.Contains(id, needle)
}

// buildOverview is the high-level architecture view with 4 subgraphs:
// CI/CD, Infrastructure, Application, Security.
//
// Port of FlowViews.buildOverview.
func buildOverview(snap *Snapshot) *Diagram {
	subgraphs := []Subgraph{}
	edges := []Edge{}
	all := snap.Nodes

	// --- CI subgraph ---
	var workflows []*model.CodeNode
	var ciJobs []*model.CodeNode
	for _, n := range all {
		if n.Kind == model.NodeModule && isCINode(n.ID) {
			workflows = append(workflows, n)
		}
		if n.Kind == model.NodeMethod && isCINode(n.ID) {
			ciJobs = append(ciJobs, n)
		}
	}
	var ciNodes []Node
	if len(workflows) > 0 || len(ciJobs) > 0 {
		ciNodes = append(ciNodes, NewNodeWithProps(
			"ci_pipelines",
			fmt.Sprintf("Pipelines x%d", len(workflows)),
			"pipeline",
			map[string]any{keyCount: len(workflows)},
		))
		if len(ciJobs) > 0 {
			ciNodes = append(ciNodes, NewNodeWithProps(
				"ci_jobs",
				fmt.Sprintf("Jobs x%d", len(ciJobs)),
				"job",
				map[string]any{keyCount: len(ciJobs)},
			))
			edges = append(edges, NewEdge("ci_pipelines", "ci_jobs"))
		}
		subgraphs = append(subgraphs, NewSubgraphWithDrillDown("ci", "CI/CD Pipeline", ciNodes, "ci"))
	}

	// --- Infrastructure subgraph ---
	var infraNodesRaw []*model.CodeNode
	for _, n := range snap.FindByKind(model.NodeInfraResource) {
		infraNodesRaw = append(infraNodesRaw, n)
	}
	for _, n := range snap.FindByKind(model.NodeAzureResource) {
		infraNodesRaw = append(infraNodesRaw, n)
	}

	if len(infraNodesRaw) > 0 {
		var k8s, docker, terraform []*model.CodeNode
		for _, n := range infraNodesRaw {
			lower := strings.ToLower(n.ID)
			switch {
			case strings.Contains(n.ID, "k8s:"):
				k8s = append(k8s, n)
			case strings.Contains(n.ID, "compose:") || strings.Contains(lower, "dockerfile"):
				docker = append(docker, n)
			case strings.Contains(n.ID, "tf:"):
				terraform = append(terraform, n)
			}
		}
		grouped := make(map[string]struct{})
		for _, n := range append(append(append([]*model.CodeNode(nil), k8s...), docker...), terraform...) {
			grouped[n.ID] = struct{}{}
		}
		var otherInfra []*model.CodeNode
		for _, n := range infraNodesRaw {
			if _, ok := grouped[n.ID]; !ok {
				otherInfra = append(otherInfra, n)
			}
		}

		var infraFlow []Node
		if len(k8s) > 0 {
			infraFlow = append(infraFlow, NewNodeWithProps("infra_k8s",
				fmt.Sprintf("K8s Resources x%d", len(k8s)), "k8s",
				map[string]any{keyCount: len(k8s)}))
		}
		if len(docker) > 0 {
			infraFlow = append(infraFlow, NewNodeWithProps("infra_docker",
				fmt.Sprintf("Docker x%d", len(docker)), "docker",
				map[string]any{keyCount: len(docker)}))
		}
		if len(terraform) > 0 {
			infraFlow = append(infraFlow, NewNodeWithProps("infra_tf",
				fmt.Sprintf("Terraform x%d", len(terraform)), "terraform",
				map[string]any{keyCount: len(terraform)}))
		}
		if len(otherInfra) > 0 {
			infraFlow = append(infraFlow, NewNodeWithProps("infra_other",
				fmt.Sprintf("Infra x%d", len(otherInfra)), "infra",
				map[string]any{keyCount: len(otherInfra)}))
		}
		if len(infraFlow) > 0 {
			subgraphs = append(subgraphs, NewSubgraphWithDrillDown("infra", "Infrastructure", infraFlow, "deploy"))
		}
	}

	// --- Application subgraph ---
	endpoints := snap.FindByKind(model.NodeEndpoint)
	entities := snap.FindByKind(model.NodeEntity)
	classes := snap.FindByKind(model.NodeClass)
	methods := snap.FindByKind(model.NodeMethod)
	var appMethods []*model.CodeNode
	for _, m := range methods {
		if !isCINode(m.ID) {
			appMethods = append(appMethods, m)
		}
	}
	components := snap.FindByKind(model.NodeComponent)
	var topics []*model.CodeNode
	topics = append(topics, snap.FindByKind(model.NodeTopic)...)
	topics = append(topics, snap.FindByKind(model.NodeQueue)...)
	dbConns := snap.FindByKind(model.NodeDatabaseConnection)

	var appNodes []Node
	hasMessaging := false
	if len(endpoints) > 0 {
		appNodes = append(appNodes, NewNodeWithProps("app_endpoints",
			fmt.Sprintf("Endpoints x%d", len(endpoints)), "endpoint",
			map[string]any{keyCount: len(endpoints)}))
	}
	if len(entities) > 0 {
		appNodes = append(appNodes, NewNodeWithProps("app_entities",
			fmt.Sprintf("Entities x%d", len(entities)), "entity",
			map[string]any{keyCount: len(entities)}))
	}
	if len(components) > 0 {
		appNodes = append(appNodes, NewNodeWithProps("app_components",
			fmt.Sprintf("Components x%d", len(components)), "component",
			map[string]any{keyCount: len(components)}))
	}
	if len(topics) > 0 {
		hasMessaging = true
		appNodes = append(appNodes, NewNodeWithProps("app_messaging",
			fmt.Sprintf("Topics/Queues x%d", len(topics)), "messaging",
			map[string]any{keyCount: len(topics)}))
	}
	if len(dbConns) > 0 {
		appNodes = append(appNodes, NewNodeWithProps("app_database",
			fmt.Sprintf("DB Connections x%d", len(dbConns)), "database",
			map[string]any{keyCount: len(dbConns)}))
	}
	if len(appNodes) == 0 && (len(classes) > 0 || len(appMethods) > 0) {
		appNodes = append(appNodes, NewNodeWithProps("app_code",
			fmt.Sprintf("Classes x%d, Methods x%d", len(classes), len(appMethods)),
			"code",
			map[string]any{"classes": len(classes), "methods": len(appMethods)}))
	}
	if len(appNodes) > 0 {
		subgraphs = append(subgraphs, NewSubgraphWithDrillDown("app", "Application", appNodes, "runtime"))
		if len(endpoints) > 0 && len(entities) > 0 {
			edges = append(edges, NewLabelEdge("app_endpoints", "app_entities", "queries"))
		}
		if len(endpoints) > 0 && hasMessaging {
			edges = append(edges, NewStyledEdge("app_endpoints", "app_messaging", "", "dotted"))
		}
	}

	// --- Security subgraph ---
	guards := snap.FindByKind(model.NodeGuard)
	middleware := snap.FindByKind(model.NodeMiddleware)
	if len(guards) > 0 || len(middleware) > 0 {
		var secNodes []Node
		if len(guards) > 0 {
			secNodes = append(secNodes, NewNodeWithProps("sec_guards",
				fmt.Sprintf("Auth Guards x%d", len(guards)), "guard",
				map[string]any{keyCount: len(guards)}))
		}
		if len(middleware) > 0 {
			secNodes = append(secNodes, NewNodeWithProps("sec_middleware",
				fmt.Sprintf("Middleware x%d", len(middleware)), "middleware",
				map[string]any{keyCount: len(middleware)}))
		}
		subgraphs = append(subgraphs, NewSubgraphWithDrillDown("security", "Security", secNodes, "auth"))
		if len(guards) > 0 && len(endpoints) > 0 {
			edges = append(edges, NewStyledEdge("sec_guards", "app_endpoints", "protects", "thick"))
		}
	}

	// --- Cross-subgraph edges ---
	if len(ciNodes) > 0 && len(infraNodesRaw) > 0 {
		if sg := findSubgraph(subgraphs, "infra"); sg != nil && len(sg.Nodes) > 0 {
			firstInfra := sg.Nodes[0].ID
			ciSource := "ci_pipelines"
			if len(ciJobs) > 0 {
				ciSource = "ci_jobs"
			}
			edges = append(edges, NewLabelEdge(ciSource, firstInfra, "deploys"))
		}
	}
	if len(infraNodesRaw) > 0 && len(appNodes) > 0 {
		if sg := findSubgraph(subgraphs, "infra"); sg != nil && len(sg.Nodes) > 0 {
			firstInfra := sg.Nodes[0].ID
			edges = append(edges, NewLabelEdge(firstInfra, appNodes[0].ID, "hosts"))
		}
	}

	stats := map[string]any{
		"total_nodes":     len(all),
		"total_edges":     len(snap.Edges),
		"endpoints":       len(endpoints),
		"entities":        len(entities),
		"guards":          len(guards),
		"components":      len(components),
		"infra_resources": len(infraNodesRaw),
	}

	d := NewDiagram("Architecture Overview", "overview")
	d.Direction = "LR"
	d.Subgraphs = subgraphs
	d.Edges = edges
	d.Stats = stats
	return d
}

// buildCIView is the CI/CD pipeline detail — workflows, jobs, triggers.
// Port of FlowViews.buildCiView.
func buildCIView(snap *Snapshot) *Diagram {
	subgraphs := []Subgraph{}
	edges := []Edge{}

	var workflows, jobs, triggers []*model.CodeNode
	for _, n := range snap.Nodes {
		if !isCINode(n.ID) {
			continue
		}
		switch n.Kind {
		case model.NodeModule:
			workflows = append(workflows, n)
		case model.NodeMethod:
			jobs = append(jobs, n)
		case model.NodeConfigKey:
			triggers = append(triggers, n)
		}
	}
	sortByID(workflows)
	sortByID(jobs)
	sortByID(triggers)

	// Trigger nodes
	if len(triggers) > 0 {
		var triggerFlow []Node
		max := len(triggers)
		if max > 10 {
			max = 10
		}
		for i := 0; i < max; i++ {
			triggerFlow = append(triggerFlow, NewNodeWithProps(
				fmt.Sprintf("trigger_%d", i),
				triggers[i].Label,
				"trigger",
				map[string]any{"source_id": triggers[i].ID},
			))
		}
		subgraphs = append(subgraphs, NewSubgraph("triggers", "Triggers", triggerFlow))
	}

	// Group jobs by workflow (use job.Module, fallback to id split).
	jobsByWF := make(map[string][]*model.CodeNode)
	for _, j := range jobs {
		wfID := j.Module
		if wfID == "" {
			if strings.Contains(j.ID, ":job:") {
				wfID = strings.SplitN(j.ID, ":job:", 2)[0]
			} else {
				wfID = "unknown"
			}
		}
		jobsByWF[wfID] = append(jobsByWF[wfID], j)
	}

	for _, wf := range workflows {
		wfJobs := jobsByWF[wf.ID]
		var jobNodes []Node
		max := len(wfJobs)
		if max > 20 {
			max = 20
		}
		for i := 0; i < max; i++ {
			j := wfJobs[i]
			props := map[string]any{}
			for _, key := range []string{"stage", "runs_on", "image"} {
				if v, ok := j.Properties[key]; ok {
					props[key] = v
				}
			}
			jobNodes = append(jobNodes, NewNodeWithProps(
				"job_"+strings.ReplaceAll(j.ID, ":", "_"),
				j.Label,
				"job",
				props,
			))
		}
		subgraphs = append(subgraphs, NewSubgraph(
			"wf_"+strings.ReplaceAll(wf.ID, ":", "_"),
			wf.Label,
			jobNodes,
		))
	}

	// Job dependency edges from DEPENDS_ON edges where both ends are CI nodes.
	for _, e := range snap.Edges {
		if e.Kind != model.EdgeDependsOn {
			continue
		}
		if !isCINode(e.SourceID) || !isCINode(e.TargetID) {
			continue
		}
		edges = append(edges, NewLabelEdge(
			"job_"+strings.ReplaceAll(e.SourceID, ":", "_"),
			"job_"+strings.ReplaceAll(e.TargetID, ":", "_"),
			"needs",
		))
	}
	// Sort edges for determinism.
	sort.Slice(edges, func(i, j int) bool {
		if edges[i].Source != edges[j].Source {
			return edges[i].Source < edges[j].Source
		}
		return edges[i].Target < edges[j].Target
	})

	// Trigger -> workflow edges.
	if len(triggers) > 0 && len(workflows) > 0 {
		for _, wf := range workflows {
			edges = append(edges, NewStyledEdge(
				"trigger_0",
				"wf_"+strings.ReplaceAll(wf.ID, ":", "_"),
				"",
				"dotted",
			))
		}
	}

	stats := map[string]any{
		"workflows": len(workflows),
		"jobs":      len(jobs),
		"triggers":  len(triggers),
	}

	d := NewDiagram("CI/CD Pipeline", "ci")
	d.Direction = "TD"
	d.Subgraphs = subgraphs
	d.Edges = edges
	d.Stats = stats
	return d
}

// buildDeployView is the deployment topology view — K8s / Docker /
// Terraform resources. Port of FlowViews.buildDeployView.
func buildDeployView(snap *Snapshot) *Diagram {
	subgraphs := []Subgraph{}
	edges := []Edge{}

	var infra []*model.CodeNode
	for _, n := range snap.Nodes {
		if n.Kind == model.NodeInfraResource || n.Kind == model.NodeAzureResource {
			infra = append(infra, n)
		}
	}
	sortByID(infra)

	var k8s, compose, tf, docker []*model.CodeNode
	for _, n := range infra {
		lower := strings.ToLower(n.ID)
		switch {
		case strings.Contains(n.ID, "k8s:"):
			k8s = append(k8s, n)
		case strings.Contains(n.ID, "compose:"):
			compose = append(compose, n)
		case strings.Contains(n.ID, "tf:"):
			tf = append(tf, n)
		case strings.Contains(lower, "dockerfile") || strings.HasPrefix(n.ID, "docker:"):
			docker = append(docker, n)
		}
	}
	grouped := make(map[string]struct{})
	for _, n := range append(append(append(append([]*model.CodeNode(nil), k8s...), compose...), tf...), docker...) {
		grouped[n.ID] = struct{}{}
	}
	var other []*model.CodeNode
	for _, n := range infra {
		if _, ok := grouped[n.ID]; !ok {
			other = append(other, n)
		}
	}

	if len(k8s) > 0 {
		subgraphs = append(subgraphs, NewSubgraph("k8s",
			fmt.Sprintf("Kubernetes (%d resources)", len(k8s)),
			makeIndexedNodes(k8s, "k8s", 20)))
	}
	if len(compose) > 0 {
		subgraphs = append(subgraphs, NewSubgraph("compose",
			fmt.Sprintf("Docker Compose (%d services)", len(compose)),
			makeIndexedNodes(compose, "compose", 20)))
	}
	if len(tf) > 0 {
		subgraphs = append(subgraphs, NewSubgraph("terraform",
			fmt.Sprintf("Terraform (%d resources)", len(tf)),
			makeIndexedNodes(tf, "tf", 20)))
	}
	if len(docker) > 0 {
		subgraphs = append(subgraphs, NewSubgraph("docker",
			fmt.Sprintf("Docker (%d images)", len(docker)),
			makeIndexedNodes(docker, "docker", 20)))
	}
	if len(other) > 0 {
		subgraphs = append(subgraphs, NewSubgraph("other_infra",
			fmt.Sprintf("Other (%d)", len(other)),
			makeIndexedNodes(other, "other", 20)))
	}

	// CONNECTS_TO and DEPENDS_ON edges between infra nodes.
	infraIDs := make(map[string]struct{}, len(infra))
	for _, n := range infra {
		infraIDs[n.ID] = struct{}{}
	}
	for _, e := range snap.Edges {
		if e.Kind != model.EdgeConnectsTo && e.Kind != model.EdgeDependsOn {
			continue
		}
		if _, ok := infraIDs[e.SourceID]; !ok {
			continue
		}
		if _, ok := infraIDs[e.TargetID]; !ok {
			continue
		}
		srcGroup := resolveGroupIndex(e.SourceID, k8s, compose, tf, docker, other)
		tgtGroup := resolveGroupIndex(e.TargetID, k8s, compose, tf, docker, other)
		edges = append(edges, NewEdge(
			srcGroup.prefix+"_"+srcGroup.index,
			tgtGroup.prefix+"_"+tgtGroup.index,
		))
	}

	stats := map[string]any{
		"k8s":       len(k8s),
		"compose":   len(compose),
		"terraform": len(tf),
		"docker":    len(docker),
	}

	d := NewDiagram("Deployment Topology", "deploy")
	d.Direction = "TD"
	d.Subgraphs = subgraphs
	d.Edges = edges
	d.Stats = stats
	return d
}

// buildRuntimeView is the runtime architecture view — endpoints, entities,
// messaging grouped by layer. Port of FlowViews.buildRuntimeView.
func buildRuntimeView(snap *Snapshot) *Diagram {
	subgraphs := []Subgraph{}
	edges := []Edge{}

	endpoints := snap.FindByKind(model.NodeEndpoint)
	entities := snap.FindByKind(model.NodeEntity)
	var topics []*model.CodeNode
	topics = append(topics, snap.FindByKind(model.NodeTopic)...)
	topics = append(topics, snap.FindByKind(model.NodeQueue)...)
	dbConns := snap.FindByKind(model.NodeDatabaseConnection)
	components := snap.FindByKind(model.NodeComponent)

	var frontendNodes, backendNodes, dataNodes []Node

	if len(endpoints) > 0 {
		var feEP, beEP []*model.CodeNode
		for _, e := range endpoints {
			if layerOf(e) == "frontend" {
				feEP = append(feEP, e)
			} else {
				beEP = append(beEP, e)
			}
		}
		if len(feEP) > 0 {
			frontendNodes = append(frontendNodes, NewNode("rt_fe_endpoints",
				fmt.Sprintf("Frontend Routes x%d", len(feEP)), "endpoint"))
		}
		if len(beEP) > 0 {
			backendNodes = append(backendNodes, NewNodeWithProps("rt_be_endpoints",
				fmt.Sprintf("API Endpoints x%d", len(beEP)), "endpoint",
				map[string]any{keyCount: len(beEP)}))
		}
	}

	if len(components) > 0 {
		frontendNodes = append(frontendNodes, NewNode("rt_components",
			fmt.Sprintf("Components x%d", len(components)), "component"))
	}

	if len(entities) > 0 {
		dataNodes = append(dataNodes, NewNode("rt_entities",
			fmt.Sprintf("Entities x%d", len(entities)), "entity"))
	}
	if len(dbConns) > 0 {
		dataNodes = append(dataNodes, NewNode("rt_database",
			fmt.Sprintf("DB Connections x%d", len(dbConns)), "database"))
	}
	if len(topics) > 0 {
		backendNodes = append(backendNodes, NewNode("rt_messaging",
			fmt.Sprintf("Messaging x%d", len(topics)), "messaging"))
	}

	if len(frontendNodes) > 0 {
		subgraphs = append(subgraphs, NewSubgraph("frontend", "Frontend", frontendNodes))
	}
	if len(backendNodes) > 0 {
		subgraphs = append(subgraphs, NewSubgraph("backend", "Backend", backendNodes))
	}
	if len(dataNodes) > 0 {
		subgraphs = append(subgraphs, NewSubgraph("data", "Data Layer", dataNodes))
	}

	if len(frontendNodes) > 0 && len(backendNodes) > 0 {
		edges = append(edges, NewLabelEdge(frontendNodes[0].ID, backendNodes[0].ID, "calls"))
	}
	if len(backendNodes) > 0 && len(dataNodes) > 0 {
		edges = append(edges, NewLabelEdge(backendNodes[0].ID, dataNodes[0].ID, "queries"))
	}

	stats := map[string]any{
		"endpoints":      len(endpoints),
		"entities":       len(entities),
		"components":     len(components),
		"topics":         len(topics),
		"db_connections": len(dbConns),
	}

	d := NewDiagram("Runtime Architecture", "runtime")
	d.Direction = "LR"
	d.Subgraphs = subgraphs
	d.Edges = edges
	d.Stats = stats
	return d
}

// buildAuthView is the auth/security view — guards, endpoints, protection
// coverage. Port of FlowViews.buildAuthView.
func buildAuthView(snap *Snapshot) *Diagram {
	subgraphs := []Subgraph{}
	edges := []Edge{}

	guards := append([]*model.CodeNode(nil), snap.FindByKind(model.NodeGuard)...)
	middleware := append([]*model.CodeNode(nil), snap.FindByKind(model.NodeMiddleware)...)
	endpoints := append([]*model.CodeNode(nil), snap.FindByKind(model.NodeEndpoint)...)
	sortByID(guards)
	sortByID(middleware)
	sortByID(endpoints)

	// Identify protected endpoints via PROTECTS edges.
	protectedIDs := make(map[string]struct{})
	for _, e := range snap.Edges {
		if e.Kind == model.EdgeProtects {
			protectedIDs[e.TargetID] = struct{}{}
		}
	}
	var protectedEndpoints, unprotectedEndpoints []*model.CodeNode
	for _, ep := range endpoints {
		if _, ok := protectedIDs[ep.ID]; ok {
			protectedEndpoints = append(protectedEndpoints, ep)
		} else {
			unprotectedEndpoints = append(unprotectedEndpoints, ep)
		}
	}

	// Group guards by auth_type.
	guardsByType := make(map[string][]*model.CodeNode)
	for _, g := range guards {
		authType := "unknown"
		if v, ok := g.Properties["auth_type"]; ok {
			authType = fmt.Sprintf("%v", v)
		}
		guardsByType[authType] = append(guardsByType[authType], g)
	}
	// Deterministic key order.
	authTypeKeys := make([]string, 0, len(guardsByType))
	for k := range guardsByType {
		authTypeKeys = append(authTypeKeys, k)
	}
	sort.Strings(authTypeKeys)

	var guardNodes []Node
	for _, authType := range authTypeKeys {
		bucket := guardsByType[authType]
		guardNodes = append(guardNodes, NewNodeWithProps(
			"auth_"+authType,
			fmt.Sprintf("%s x%d", authType, len(bucket)),
			"guard",
			map[string]any{"auth_type": authType, keyCount: len(bucket)},
		))
	}
	if len(middleware) > 0 {
		guardNodes = append(guardNodes, NewNodeWithProps("auth_middleware",
			fmt.Sprintf("Middleware x%d", len(middleware)), "middleware",
			map[string]any{keyCount: len(middleware)}))
	}
	if len(guardNodes) > 0 {
		subgraphs = append(subgraphs, NewSubgraph("guards", "Auth Guards", guardNodes))
	}

	var epNodes []Node
	if len(protectedEndpoints) > 0 {
		epNodes = append(epNodes, NewNodeWithStyle("ep_protected",
			fmt.Sprintf("Protected x%d", len(protectedEndpoints)),
			"endpoint", "success",
			map[string]any{keyCount: len(protectedEndpoints)}))
	}
	if len(unprotectedEndpoints) > 0 {
		epNodes = append(epNodes, NewNodeWithStyle("ep_unprotected",
			fmt.Sprintf("Unprotected x%d", len(unprotectedEndpoints)),
			"endpoint", "danger",
			map[string]any{keyCount: len(unprotectedEndpoints)}))
	}
	if len(epNodes) > 0 {
		subgraphs = append(subgraphs, NewSubgraph("endpoints", "Endpoints", epNodes))
	}

	// Guards -> protected edges.
	hasProtected := false
	for _, en := range epNodes {
		if en.ID == "ep_protected" {
			hasProtected = true
			break
		}
	}
	if hasProtected {
		for _, gn := range guardNodes {
			edges = append(edges, NewStyledEdge(gn.ID, "ep_protected", "protects", "thick"))
		}
	}

	coverage := 0.0
	if len(endpoints) > 0 {
		coverage = float64(len(protectedEndpoints)) / float64(len(endpoints)) * 100
	}
	// Round to one decimal — math.Round(x*10)/10.
	coverage = math.Round(coverage*10) / 10

	stats := map[string]any{
		"guards":       len(guards),
		"middleware":   len(middleware),
		"protected":    len(protectedEndpoints),
		"unprotected":  len(unprotectedEndpoints),
		"coverage_pct": coverage,
	}

	d := NewDiagram("Auth & Security", "auth")
	d.Direction = "LR"
	d.Subgraphs = subgraphs
	d.Edges = edges
	d.Stats = stats
	return d
}

// --- Helpers ---

// findSubgraph returns a pointer to the subgraph with the given ID, or nil.
func findSubgraph(subgraphs []Subgraph, id string) *Subgraph {
	for i := range subgraphs {
		if subgraphs[i].ID == id {
			return &subgraphs[i]
		}
	}
	return nil
}

// sortByID sorts a slice of nodes by ID in place.
func sortByID(nodes []*model.CodeNode) {
	sort.Slice(nodes, func(i, j int) bool { return nodes[i].ID < nodes[j].ID })
}

// makeIndexedNodes builds at most maxNodes flow Nodes with IDs of the form
// prefix_{i}. Mirrors the Java FlowViews.makeNodes helper.
func makeIndexedNodes(nodes []*model.CodeNode, prefix string, maxNodes int) []Node {
	var out []Node
	limit := len(nodes)
	if limit > maxNodes {
		limit = maxNodes
	}
	for i := 0; i < limit; i++ {
		n := nodes[i]
		props := map[string]any{}
		for _, key := range []string{"kind", "namespace", "image", "resource_type", "provider"} {
			if v, ok := n.Properties[key]; ok {
				props[key] = v
			}
		}
		out = append(out, NewNodeWithProps(
			fmt.Sprintf("%s_%d", prefix, i),
			n.Label,
			prefix,
			props,
		))
	}
	return out
}

// groupAssignment captures where in the deploy view a node was placed.
type groupAssignment struct {
	prefix string
	index  string
}

// resolveGroupIndex returns the (prefix, index) tuple for a node in the
// deploy view, falling back to "other" when no group matched. Mirrors the
// Java FlowViews.resolveGroupIndex helper.
func resolveGroupIndex(id string, k8s, compose, tf, docker, other []*model.CodeNode) groupAssignment {
	if idx := indexOf(k8s, id); idx >= 0 {
		return groupAssignment{"k8s", fmt.Sprintf("%d", idx)}
	}
	if idx := indexOf(compose, id); idx >= 0 {
		return groupAssignment{"compose", fmt.Sprintf("%d", idx)}
	}
	if idx := indexOf(tf, id); idx >= 0 {
		return groupAssignment{"tf", fmt.Sprintf("%d", idx)}
	}
	if idx := indexOf(docker, id); idx >= 0 {
		return groupAssignment{"docker", fmt.Sprintf("%d", idx)}
	}
	if idx := indexOf(other, id); idx >= 0 {
		return groupAssignment{"other", fmt.Sprintf("%d", idx)}
	}
	return groupAssignment{"other", "0"}
}

// indexOf returns the position of the node with the given ID in the slice,
// or -1 when absent.
func indexOf(nodes []*model.CodeNode, id string) int {
	for i, n := range nodes {
		if n.ID == id {
			return i
		}
	}
	return -1
}

// layerOf returns the node's `layer` property as a string. Mirrors the
// Java side's `getProperties().get("layer")` pattern.
func layerOf(n *model.CodeNode) string {
	if v, ok := n.Properties["layer"].(string); ok {
		return v
	}
	// Fall back to the typed Layer field — its String() yields "frontend"
	// / "backend" etc. for the standard layers.
	return n.Layer.String()
}
