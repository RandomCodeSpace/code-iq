package iac

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const bicepSource = `param location string
param storageName string

resource storage 'Microsoft.Storage/storageAccounts@2023-01-01' = {
  name: storageName
  location: location
}

resource someInfra 'Other.NotMs/thing@1.0' = {
  name: 'x'
}

module networking 'modules/network.bicep' = {
  name: 'net'
}
`

func TestBicepPositive(t *testing.T) {
	d := NewBicepDetector()
	r := d.Detect(&detector.Context{FilePath: "main.bicep", Language: "bicep", Content: bicepSource})

	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	if kinds[model.NodeAzureResource] != 1 {
		t.Errorf("expected 1 AZURE_RESOURCE, got %d", kinds[model.NodeAzureResource])
	}
	// Other.NotMs/thing → INFRA_RESOURCE; module → INFRA_RESOURCE → 2
	if kinds[model.NodeInfraResource] != 2 {
		t.Errorf("expected 2 INFRA_RESOURCE (non-Microsoft + module), got %d", kinds[model.NodeInfraResource])
	}
	if kinds[model.NodeConfigKey] != 2 {
		t.Errorf("expected 2 CONFIG_KEY (params), got %d", kinds[model.NodeConfigKey])
	}

	if len(r.Edges) != 1 || r.Edges[0].Kind != model.EdgeDependsOn {
		t.Errorf("expected 1 DEPENDS_ON edge for module, got %d", len(r.Edges))
	}
}

func TestBicepApiVersion(t *testing.T) {
	d := NewBicepDetector()
	r := d.Detect(&detector.Context{FilePath: "main.bicep", Language: "bicep", Content: bicepSource})
	for _, n := range r.Nodes {
		if n.Kind == model.NodeAzureResource {
			if n.Properties["api_version"] != "2023-01-01" {
				t.Errorf("api_version = %v", n.Properties["api_version"])
			}
			if n.Properties["azure_type"] != "Microsoft.Storage/storageAccounts" {
				t.Errorf("azure_type = %v", n.Properties["azure_type"])
			}
		}
	}
}

func TestBicepNegative(t *testing.T) {
	d := NewBicepDetector()
	r := d.Detect(&detector.Context{FilePath: "x.bicep", Language: "bicep", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestBicepDeterminism(t *testing.T) {
	d := NewBicepDetector()
	ctx := &detector.Context{FilePath: "main.bicep", Language: "bicep", Content: bicepSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
