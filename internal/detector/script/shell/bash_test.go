package shell

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const bashSource = `#!/usr/bin/env bash
source ./lib.sh
. helpers.sh

function deploy {
    aws s3 cp file.txt s3://bucket
    docker build .
    kubectl apply -f deploy.yaml
}

cleanup() {
    docker rm -f foo
    # this comment should be ignored: aws
}

export AWS_PROFILE=prod
export REGION=us-east-1
`

func TestBashPositive(t *testing.T) {
	d := NewBashDetector()
	r := d.Detect(&detector.Context{FilePath: "deploy.sh", Language: "bash", Content: bashSource})

	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	// 1 shebang module + 1 file-anchor module (emitted by import/calls anchor helpers)
	if kinds[model.NodeModule] != 2 {
		t.Errorf("expected 2 MODULE (shebang + file anchor), got %d", kinds[model.NodeModule])
	}
	// 2 functions (deploy, cleanup)
	if kinds[model.NodeMethod] != 2 {
		t.Errorf("expected 2 METHOD, got %d", kinds[model.NodeMethod])
	}
	// 2 exports
	if kinds[model.NodeConfigDefinition] != 2 {
		t.Errorf("expected 2 CONFIG_DEFINITION (exports), got %d", kinds[model.NodeConfigDefinition])
	}

	importEdges := 0
	callEdges := 0
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeImports:
			importEdges++
		case model.EdgeCalls:
			callEdges++
		}
	}
	// 2 source imports
	if importEdges != 2 {
		t.Errorf("expected 2 import edges, got %d", importEdges)
	}
	// aws, docker, kubectl tools — deduped — 3 unique
	if callEdges != 3 {
		t.Errorf("expected 3 unique CALLS (tools), got %d", callEdges)
	}
}

func TestBashNegative(t *testing.T) {
	d := NewBashDetector()
	r := d.Detect(&detector.Context{FilePath: "x.sh", Language: "bash", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

// TestBashImports_EdgeSurvivesSnapshot verifies that the anchor nodes emitted
// alongside source/calls edges are present in the detector result, so
// GraphBuilder.Snapshot's phantom-drop filter does not discard them.
func TestBashImports_EdgeSurvivesSnapshot(t *testing.T) {
	d := NewBashDetector()
	r := d.Detect(&detector.Context{FilePath: "deploy.sh", Language: "bash", Content: bashSource})

	var moduleNodes, externalNodes int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeModule:
			moduleNodes++
		case model.NodeExternal:
			externalNodes++
		}
	}
	if moduleNodes == 0 {
		t.Fatal("expected at least one MODULE anchor node for the file endpoint")
	}
	if externalNodes == 0 {
		t.Fatal("expected at least one EXTERNAL anchor node for imported/called targets")
	}

	var importEdges, callEdges int
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeImports:
			importEdges++
		case model.EdgeCalls:
			callEdges++
		}
	}
	if importEdges == 0 {
		t.Fatal("expected at least one surviving imports edge, got 0")
	}
	if callEdges == 0 {
		t.Fatal("expected at least one surviving calls edge, got 0")
	}
}

func TestBashDeterminism(t *testing.T) {
	d := NewBashDetector()
	ctx := &detector.Context{FilePath: "deploy.sh", Language: "bash", Content: bashSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
