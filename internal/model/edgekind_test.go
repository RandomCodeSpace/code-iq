package model

import (
	"encoding/json"
	"testing"
)

func TestEdgeKindCount(t *testing.T) {
	if got, want := len(AllEdgeKinds()), 28; got != want {
		t.Fatalf("AllEdgeKinds count = %d, want %d", got, want)
	}
}

func TestEdgeKindValues(t *testing.T) {
	cases := map[EdgeKind]string{
		EdgeDependsOn:       "depends_on",
		EdgeImports:         "imports",
		EdgeExtends:         "extends",
		EdgeImplements:      "implements",
		EdgeCalls:           "calls",
		EdgeInjects:         "injects",
		EdgeExposes:         "exposes",
		EdgeQueries:         "queries",
		EdgeMapsTo:          "maps_to",
		EdgeProduces:        "produces",
		EdgeConsumes:        "consumes",
		EdgePublishes:       "publishes",
		EdgeListens:         "listens",
		EdgeInvokesRMI:      "invokes_rmi",
		EdgeExportsRMI:      "exports_rmi",
		EdgeReadsConfig:     "reads_config",
		EdgeMigrates:        "migrates",
		EdgeContains:        "contains",
		EdgeDefines:         "defines",
		EdgeOverrides:       "overrides",
		EdgeConnectsTo:      "connects_to",
		EdgeTriggers:        "triggers",
		EdgeProvisions:      "provisions",
		EdgeSendsTo:         "sends_to",
		EdgeReceivesFrom:    "receives_from",
		EdgeProtects:        "protects",
		EdgeRenders:         "renders",
		EdgeReferencesTable: "references_table",
	}
	for k, want := range cases {
		if got := k.String(); got != want {
			t.Errorf("%v.String() = %q, want %q", k, got, want)
		}
	}
}

func TestEdgeKindFromString(t *testing.T) {
	for _, k := range AllEdgeKinds() {
		got, err := ParseEdgeKind(k.String())
		if err != nil {
			t.Errorf("ParseEdgeKind(%q) error = %v", k.String(), err)
			continue
		}
		if got != k {
			t.Errorf("round-trip: %q → %v, want %v", k.String(), got, k)
		}
	}
	if _, err := ParseEdgeKind("bogus"); err == nil {
		t.Error("ParseEdgeKind(\"bogus\") err = nil, want non-nil")
	}
}

func TestEdgeKindJSON(t *testing.T) {
	b, err := json.Marshal(EdgeReferencesTable)
	if err != nil {
		t.Fatal(err)
	}
	if string(b) != `"references_table"` {
		t.Fatalf("Marshal = %s", b)
	}
	var k EdgeKind
	if err := json.Unmarshal([]byte(`"calls"`), &k); err != nil {
		t.Fatal(err)
	}
	if k != EdgeCalls {
		t.Fatalf("Unmarshal = %v, want EdgeCalls", k)
	}
}
