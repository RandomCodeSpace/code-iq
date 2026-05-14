package model

import (
	"encoding/json"
	"fmt"
)

// EdgeKind enumerates the 28 edge types in the codeiq graph.
// String values MUST match the Java EdgeKind enum 1:1 (see
// src/main/java/io/github/randomcodespace/iq/model/EdgeKind.java).
type EdgeKind int

const (
	EdgeDependsOn EdgeKind = iota
	EdgeImports
	EdgeExtends
	EdgeImplements
	EdgeCalls
	EdgeInjects
	EdgeExposes
	EdgeQueries
	EdgeMapsTo
	EdgeProduces
	EdgeConsumes
	EdgePublishes
	EdgeListens
	EdgeInvokesRMI
	EdgeExportsRMI
	EdgeReadsConfig
	EdgeMigrates
	EdgeContains
	EdgeDefines
	EdgeOverrides
	EdgeConnectsTo
	EdgeTriggers
	EdgeProvisions
	EdgeSendsTo
	EdgeReceivesFrom
	EdgeProtects
	EdgeRenders
	EdgeReferencesTable
)

var edgeKindNames = [...]string{
	"depends_on",
	"imports",
	"extends",
	"implements",
	"calls",
	"injects",
	"exposes",
	"queries",
	"maps_to",
	"produces",
	"consumes",
	"publishes",
	"listens",
	"invokes_rmi",
	"exports_rmi",
	"reads_config",
	"migrates",
	"contains",
	"defines",
	"overrides",
	"connects_to",
	"triggers",
	"provisions",
	"sends_to",
	"receives_from",
	"protects",
	"renders",
	"references_table",
}

func (k EdgeKind) String() string {
	if int(k) < 0 || int(k) >= len(edgeKindNames) {
		return fmt.Sprintf("edgekind(%d)", int(k))
	}
	return edgeKindNames[k]
}

func AllEdgeKinds() []EdgeKind {
	out := make([]EdgeKind, len(edgeKindNames))
	for i := range edgeKindNames {
		out[i] = EdgeKind(i)
	}
	return out
}

func ParseEdgeKind(s string) (EdgeKind, error) {
	for i, name := range edgeKindNames {
		if name == s {
			return EdgeKind(i), nil
		}
	}
	return 0, fmt.Errorf("unknown EdgeKind: %q", s)
}

func (k EdgeKind) MarshalJSON() ([]byte, error) {
	return json.Marshal(k.String())
}

func (k *EdgeKind) UnmarshalJSON(data []byte) error {
	var s string
	if err := json.Unmarshal(data, &s); err != nil {
		return err
	}
	parsed, err := ParseEdgeKind(s)
	if err != nil {
		return err
	}
	*k = parsed
	return nil
}
