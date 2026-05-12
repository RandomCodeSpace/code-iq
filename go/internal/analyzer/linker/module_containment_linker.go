package linker

import (
	"fmt"
	"sort"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// ModuleContainmentLinker groups nodes by their Module field and emits MODULE
// nodes plus CONTAINS edges pointing at each member.
//
// Mirrors src/main/java/io/github/randomcodespace/iq/analyzer/linker/ModuleContainmentLinker.java
// (lines 30-97). MODULE-kind nodes are excluded from membership grouping so a
// module never contains itself; duplicate CONTAINS edges are suppressed.
type ModuleContainmentLinker struct{}

// NewModuleContainmentLinker returns a stateless linker.
func NewModuleContainmentLinker() *ModuleContainmentLinker {
	return &ModuleContainmentLinker{}
}

// Link emits the new MODULE nodes and CONTAINS edges. Modules iterate in
// alphabetical order; members within a module iterate in ID order — making
// the output stable across runs.
func (l *ModuleContainmentLinker) Link(nodes []*model.CodeNode, edges []*model.CodeEdge) Result {
	existingModules := map[string]struct{}{}
	for _, n := range nodes {
		if n.Kind == model.NodeModule {
			existingModules[n.ID] = struct{}{}
		}
	}

	byModule := map[string][]*model.CodeNode{}
	for _, n := range nodes {
		if n.Kind == model.NodeModule || n.Module == "" {
			continue
		}
		byModule[n.Module] = append(byModule[n.Module], n)
	}
	if len(byModule) == 0 {
		return Result{}
	}

	existingContains := map[string]struct{}{}
	for _, e := range edges {
		if e.Kind == model.EdgeContains {
			existingContains[e.SourceID+"->"+e.TargetID] = struct{}{}
		}
	}

	moduleNames := make([]string, 0, len(byModule))
	for m := range byModule {
		moduleNames = append(moduleNames, m)
	}
	sort.Strings(moduleNames)

	var newNodes []*model.CodeNode
	var newEdges []*model.CodeEdge
	for _, m := range moduleNames {
		moduleID := "module:" + m
		if _, ok := existingModules[moduleID]; !ok {
			newNodes = append(newNodes, &model.CodeNode{
				ID:     moduleID,
				Kind:   model.NodeModule,
				Label:  m,
				FQN:    m,
				Module: m,
			})
			existingModules[moduleID] = struct{}{}
		}
		members := byModule[m]
		sort.Slice(members, func(i, j int) bool { return members[i].ID < members[j].ID })
		for _, mem := range members {
			key := moduleID + "->" + mem.ID
			if _, ok := existingContains[key]; ok {
				continue
			}
			newEdges = append(newEdges, &model.CodeEdge{
				ID:         fmt.Sprintf("module-link:%s->%s", moduleID, mem.ID),
				Kind:       model.EdgeContains,
				SourceID:   moduleID,
				TargetID:   mem.ID,
				Properties: map[string]any{"inferred": true},
			})
			existingContains[key] = struct{}{}
		}
	}
	return Result{Nodes: newNodes, Edges: newEdges}
}
