package proto

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const protoSource = `syntax = "proto3";

package my.api.v1;

import "google/protobuf/timestamp.proto";

service UserService {
    rpc GetUser (GetUserRequest) returns (GetUserResponse);
    rpc ListUsers (ListUsersRequest) returns (ListUsersResponse);
}

message GetUserRequest {
    string id = 1;
}

message GetUserResponse {
    string name = 1;
}

message ListUsersRequest {}

message ListUsersResponse {
    repeated GetUserResponse users = 1;
}
`

func TestProtoPositive(t *testing.T) {
	d := NewStructureDetector()
	r := d.Detect(&detector.Context{FilePath: "api.proto", Language: "proto", Content: protoSource})

	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	if kinds[model.NodeConfigKey] != 1 {
		t.Errorf("expected 1 CONFIG_KEY (package), got %d", kinds[model.NodeConfigKey])
	}
	if kinds[model.NodeInterface] != 1 {
		t.Errorf("expected 1 INTERFACE (service), got %d", kinds[model.NodeInterface])
	}
	if kinds[model.NodeMethod] != 2 {
		t.Errorf("expected 2 METHOD (RPCs), got %d", kinds[model.NodeMethod])
	}
	if kinds[model.NodeProtocolMessage] != 4 {
		t.Errorf("expected 4 PROTOCOL_MESSAGE, got %d", kinds[model.NodeProtocolMessage])
	}

	imports := 0
	contains := 0
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeImports:
			imports++
		case model.EdgeContains:
			contains++
		}
	}
	if imports != 1 {
		t.Errorf("expected 1 IMPORTS edge, got %d", imports)
	}
	if contains != 2 {
		t.Errorf("expected 2 CONTAINS edges (service→rpcs), got %d", contains)
	}
}

func TestProtoNegative(t *testing.T) {
	d := NewStructureDetector()
	r := d.Detect(&detector.Context{FilePath: "x.proto", Language: "proto", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestProtoDeterminism(t *testing.T) {
	d := NewStructureDetector()
	ctx := &detector.Context{FilePath: "api.proto", Language: "proto", Content: protoSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
