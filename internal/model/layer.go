package model

import (
	"encoding/json"
	"fmt"
)

// Layer is the five-way layer classification stamped by LayerClassifier
// (phase 2). Phase 1 detectors emit LayerUnknown; classification is deferred
// to phase 2's analyzer.LayerClassifier.
type Layer int

const (
	LayerFrontend Layer = iota
	LayerBackend
	LayerInfra
	LayerShared
	LayerUnknown
)

var layerNames = [...]string{"frontend", "backend", "infra", "shared", "unknown"}

func (l Layer) String() string {
	if int(l) < 0 || int(l) >= len(layerNames) {
		return fmt.Sprintf("layer(%d)", int(l))
	}
	return layerNames[l]
}

func AllLayers() []Layer {
	out := make([]Layer, len(layerNames))
	for i := range layerNames {
		out[i] = Layer(i)
	}
	return out
}

func ParseLayer(s string) (Layer, error) {
	for i, name := range layerNames {
		if name == s {
			return Layer(i), nil
		}
	}
	return 0, fmt.Errorf("unknown Layer: %q", s)
}

func (l Layer) MarshalJSON() ([]byte, error) {
	return json.Marshal(l.String())
}

func (l *Layer) UnmarshalJSON(data []byte) error {
	var s string
	if err := json.Unmarshal(data, &s); err != nil {
		return err
	}
	parsed, err := ParseLayer(s)
	if err != nil {
		return err
	}
	*l = parsed
	return nil
}
