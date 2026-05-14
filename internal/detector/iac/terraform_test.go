package iac

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const tfSource = `provider "aws" {
  region = "us-east-1"
}

resource "aws_s3_bucket" "logs" {
  bucket = "my-logs"
}

data "aws_caller_identity" "current" {}

module "vpc" {
  source = "./modules/vpc"
}

variable "env" {
  type = string
}

output "bucket_name" {
  value = aws_s3_bucket.logs.id
}
`

func TestTerraformPositive(t *testing.T) {
	d := NewTerraformDetector()
	r := d.Detect(&detector.Context{FilePath: "main.tf", Language: "terraform", Content: tfSource})
	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	// 1 resource + 1 data + 1 provider = 3 INFRA_RESOURCE
	if kinds[model.NodeInfraResource] != 3 {
		t.Errorf("expected 3 INFRA_RESOURCE, got %d", kinds[model.NodeInfraResource])
	}
	// 1 module
	if kinds[model.NodeModule] != 1 {
		t.Errorf("expected 1 MODULE, got %d", kinds[model.NodeModule])
	}
	// 1 variable + 1 output = 2 CONFIG_DEFINITION
	if kinds[model.NodeConfigDefinition] != 2 {
		t.Errorf("expected 2 CONFIG_DEFINITION, got %d", kinds[model.NodeConfigDefinition])
	}

	// 1 DEPENDS_ON edge for module → source
	if len(r.Edges) != 1 || r.Edges[0].Kind != model.EdgeDependsOn {
		t.Errorf("expected 1 DEPENDS_ON edge, got %d", len(r.Edges))
	}
}

func TestTerraformProviderExtraction(t *testing.T) {
	d := NewTerraformDetector()
	r := d.Detect(&detector.Context{FilePath: "main.tf", Language: "terraform", Content: tfSource})
	for _, n := range r.Nodes {
		if n.Properties["resource_type"] == "aws_s3_bucket" {
			if n.Properties["provider"] != "aws" {
				t.Errorf("provider = %v, want aws", n.Properties["provider"])
			}
		}
	}
}

func TestTerraformNegative(t *testing.T) {
	d := NewTerraformDetector()
	r := d.Detect(&detector.Context{FilePath: "x.tf", Language: "terraform", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestTerraformDeterminism(t *testing.T) {
	d := NewTerraformDetector()
	ctx := &detector.Context{FilePath: "main.tf", Language: "terraform", Content: tfSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
