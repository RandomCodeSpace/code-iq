package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

func TestCloudFormationDetector_Resources(t *testing.T) {
	d := NewCloudFormationDetector()
	ctx := &detector.Context{
		FilePath: "template.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"AWSTemplateFormatVersion": "2010-09-09",
				"Resources": map[string]any{
					"MyBucket": map[string]any{"Type": "AWS::S3::Bucket"},
					"MyQueue":  map[string]any{"Type": "AWS::SQS::Queue", "Properties": map[string]any{"QueueName": map[string]any{"Ref": "MyBucket"}}},
				},
			},
		},
	}
	r := d.Detect(ctx)
	var infraCount int
	for _, n := range r.Nodes {
		if n.Kind == model.NodeInfraResource {
			infraCount++
		}
	}
	if infraCount != 2 {
		t.Errorf("INFRA_RESOURCE count = %d, want 2", infraCount)
	}
	var sawDep bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeDependsOn {
			sawDep = true
		}
	}
	if !sawDep {
		t.Fatal("missing DEPENDS_ON")
	}
}

func TestCloudFormationDetector_Parameters(t *testing.T) {
	d := NewCloudFormationDetector()
	ctx := &detector.Context{
		FilePath: "stack.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{
				"AWSTemplateFormatVersion": "2010-09-09",
				"Parameters":               map[string]any{"Env": map[string]any{"Type": "String", "Default": "dev"}},
				"Resources":                map[string]any{},
			},
		},
	}
	r := d.Detect(ctx)
	var sawCfgDef bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeConfigDefinition {
			sawCfgDef = true
		}
	}
	if !sawCfgDef {
		t.Fatal("missing CONFIG_DEFINITION")
	}
}

func TestCloudFormationDetector_NotCfn(t *testing.T) {
	d := NewCloudFormationDetector()
	ctx := &detector.Context{
		FilePath: "config.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{"name": "not-cfn"},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestCloudFormationDetector_Deterministic(t *testing.T) {
	d := NewCloudFormationDetector()
	ctx := &detector.Context{
		FilePath: "cfn.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"AWSTemplateFormatVersion": "2010-09-09",
				"Resources":                map[string]any{"Bucket": map[string]any{"Type": "AWS::S3::Bucket"}},
			},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
