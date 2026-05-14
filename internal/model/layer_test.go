package model

import (
	"encoding/json"
	"testing"
)

func TestLayerValues(t *testing.T) {
	cases := map[Layer]string{
		LayerFrontend: "frontend",
		LayerBackend:  "backend",
		LayerInfra:    "infra",
		LayerShared:   "shared",
		LayerUnknown:  "unknown",
	}
	for l, want := range cases {
		if got := l.String(); got != want {
			t.Errorf("%v.String() = %q, want %q", l, got, want)
		}
	}
}

func TestLayerParse(t *testing.T) {
	for _, l := range AllLayers() {
		got, err := ParseLayer(l.String())
		if err != nil {
			t.Errorf("ParseLayer(%q) error = %v", l.String(), err)
		}
		if got != l {
			t.Errorf("round-trip mismatch: %v != %v", got, l)
		}
	}
	if _, err := ParseLayer("middle"); err == nil {
		t.Error("ParseLayer(\"middle\") err = nil")
	}
}

func TestLayerJSON(t *testing.T) {
	b, err := json.Marshal(LayerBackend)
	if err != nil {
		t.Fatal(err)
	}
	if string(b) != `"backend"` {
		t.Fatalf("Marshal = %s", b)
	}
}
