package shell

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
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
	// 1 shebang module
	if kinds[model.NodeModule] != 1 {
		t.Errorf("expected 1 MODULE (shebang), got %d", kinds[model.NodeModule])
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

func TestBashDeterminism(t *testing.T) {
	d := NewBashDetector()
	ctx := &detector.Context{FilePath: "deploy.sh", Language: "bash", Content: bashSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
