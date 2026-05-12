package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestPropertiesDetector_SpringConfig(t *testing.T) {
	d := NewPropertiesDetector()
	ctx := &detector.Context{
		FilePath: "application.properties",
		Language: "properties",
		ParsedData: map[string]any{
			"type": "properties",
			"data": map[string]any{
				"spring.datasource.url":      "jdbc:mysql://localhost/db",
				"spring.datasource.username": "root",
				"server.port":                "8080",
			},
		},
	}
	r := d.Detect(ctx)
	// 1 file + 3 keys
	if len(r.Nodes) != 4 {
		t.Fatalf("expected 4 nodes, got %d", len(r.Nodes))
	}
	// jdbc URL key should be a DATABASE_CONNECTION
	var dbNode *model.CodeNode
	for _, n := range r.Nodes {
		if n.Kind == model.NodeDatabaseConnection {
			dbNode = n
		}
	}
	if dbNode == nil {
		t.Fatal("missing DATABASE_CONNECTION node")
	}
	if dbNode.Label != "MySQL" {
		t.Errorf("label = %q, want MySQL", dbNode.Label)
	}
	if dbNode.Properties["db_type"] != "MySQL" {
		t.Errorf("db_type = %v, want MySQL", dbNode.Properties["db_type"])
	}
	// username should remain CONFIG_KEY (no jdbc: value)
	var unameNode *model.CodeNode
	for _, n := range r.Nodes {
		if n.Label == "spring.datasource.username" {
			unameNode = n
		}
	}
	if unameNode == nil || unameNode.Kind != model.NodeConfigKey {
		t.Errorf("username should be CONFIG_KEY")
	}
	// server.port should NOT have spring_config marker
	var portNode *model.CodeNode
	for _, n := range r.Nodes {
		if n.Label == "server.port" {
			portNode = n
		}
	}
	if portNode == nil {
		t.Fatal("missing server.port node")
	}
	if _, ok := portNode.Properties["spring_config"]; ok {
		t.Errorf("server.port shouldn't have spring_config")
	}
}

func TestPropertiesDetector_PostgresUrl(t *testing.T) {
	d := NewPropertiesDetector()
	ctx := &detector.Context{
		FilePath: "application.properties",
		Language: "properties",
		ParsedData: map[string]any{
			"type": "properties",
			"data": map[string]any{
				"spring.datasource.url":               "jdbc:postgresql://db-host:5432/mydb",
				"spring.datasource.password":          "secret",
				"spring.datasource.driver-class-name": "org.postgresql.Driver",
			},
		},
	}
	r := d.Detect(ctx)
	dbCount := 0
	for _, n := range r.Nodes {
		if n.Kind == model.NodeDatabaseConnection {
			dbCount++
			if n.Label != "PostgreSQL" {
				t.Errorf("label = %q, want PostgreSQL", n.Label)
			}
		}
	}
	if dbCount != 1 {
		t.Errorf("DATABASE_CONNECTION count = %d, want 1", dbCount)
	}
}

func TestPropertiesDetector_NonUrlIsConfigKey(t *testing.T) {
	d := NewPropertiesDetector()
	ctx := &detector.Context{
		FilePath: "application.properties",
		Language: "properties",
		ParsedData: map[string]any{
			"type": "properties",
			"data": map[string]any{
				"spring.datasource.hikari.maximum-pool-size": "10",
				"spring.datasource.username":                 "admin",
			},
		},
	}
	r := d.Detect(ctx)
	for _, n := range r.Nodes {
		if n.Kind == model.NodeDatabaseConnection {
			t.Errorf("unexpected DATABASE_CONNECTION node %+v", n)
		}
	}
}

func TestPropertiesDetector_NegativeWrongType(t *testing.T) {
	d := NewPropertiesDetector()
	ctx := &detector.Context{
		FilePath: "app.properties",
		Language: "properties",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{"key": "value"},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestPropertiesDetector_Deterministic(t *testing.T) {
	d := NewPropertiesDetector()
	ctx := &detector.Context{
		FilePath: "app.properties",
		Language: "properties",
		ParsedData: map[string]any{
			"type": "properties",
			"data": map[string]any{"key1": "val1", "key2": "val2"},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic")
		}
	}
}
