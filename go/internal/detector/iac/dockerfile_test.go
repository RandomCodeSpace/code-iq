package iac

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const dockerfileSource = `ARG NODE_VERSION=18
FROM node:${NODE_VERSION}-alpine AS builder
ENV NODE_ENV=production
LABEL maintainer="me@example.com"
COPY . /app
RUN npm ci

FROM alpine:3.19
COPY --from=builder /app/dist /app
EXPOSE 8080
EXPOSE 9090
`

func TestDockerfilePositive(t *testing.T) {
	d := NewDockerfileDetector()
	r := d.Detect(&detector.Context{FilePath: "Dockerfile", Language: "dockerfile", Content: dockerfileSource})

	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	// 2 FROM = 2 INFRA_RESOURCE
	if kinds[model.NodeInfraResource] != 2 {
		t.Errorf("expected 2 INFRA_RESOURCE (FROM), got %d", kinds[model.NodeInfraResource])
	}
	// 2 EXPOSE = 2 ENDPOINT
	if kinds[model.NodeEndpoint] != 2 {
		t.Errorf("expected 2 ENDPOINT (EXPOSE), got %d", kinds[model.NodeEndpoint])
	}
	// 1 ENV + 1 LABEL + 1 ARG = 3 CONFIG_DEFINITION
	if kinds[model.NodeConfigDefinition] != 3 {
		t.Errorf("expected 3 CONFIG_DEFINITION, got %d", kinds[model.NodeConfigDefinition])
	}

	// Edges: 2 base-image DEPENDS_ON + 1 multi-stage DEPENDS_ON
	dependsEdges := 0
	for _, e := range r.Edges {
		if e.Kind == model.EdgeDependsOn {
			dependsEdges++
		}
	}
	if dependsEdges != 3 {
		t.Errorf("expected 3 DEPENDS_ON edges, got %d", dependsEdges)
	}
}

func TestDockerfileStageAlias(t *testing.T) {
	d := NewDockerfileDetector()
	r := d.Detect(&detector.Context{FilePath: "Dockerfile", Language: "dockerfile", Content: dockerfileSource})
	foundBuilder := false
	for _, n := range r.Nodes {
		if n.Kind == model.NodeInfraResource && n.Properties["stage_alias"] == "builder" {
			foundBuilder = true
			if n.Properties["image_name"] != "node" {
				t.Errorf("image_name = %v", n.Properties["image_name"])
			}
		}
	}
	if !foundBuilder {
		t.Error("expected builder stage")
	}
}

func TestDockerfileNegative(t *testing.T) {
	d := NewDockerfileDetector()
	r := d.Detect(&detector.Context{FilePath: "x", Language: "dockerfile", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

// TestDockerfileImports_EdgeSurvivesSnapshot verifies that the anchor nodes
// emitted alongside FROM depends_on edges are present in the detector result,
// so GraphBuilder.Snapshot's phantom-drop filter does not discard them.
func TestDockerfileImports_EdgeSurvivesSnapshot(t *testing.T) {
	d := NewDockerfileDetector()
	r := d.Detect(&detector.Context{FilePath: "Dockerfile", Language: "dockerfile", Content: dockerfileSource})

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
		t.Fatal("expected at least one EXTERNAL anchor node for the image target")
	}

	dependsEdges := 0
	for _, e := range r.Edges {
		if e.Kind == model.EdgeDependsOn {
			dependsEdges++
		}
	}
	if dependsEdges == 0 {
		t.Fatal("expected at least one surviving depends_on edge, got 0")
	}
}

func TestDockerfileDeterminism(t *testing.T) {
	d := NewDockerfileDetector()
	ctx := &detector.Context{FilePath: "Dockerfile", Language: "dockerfile", Content: dockerfileSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
