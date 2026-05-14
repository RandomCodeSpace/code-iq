package model

import (
	"encoding/json"
	"testing"
)

func TestNodeKindCount(t *testing.T) {
	if got, want := len(AllNodeKinds()), 34; got != want {
		t.Fatalf("AllNodeKinds count = %d, want %d", got, want)
	}
}

func TestNodeKindValues(t *testing.T) {
	cases := map[NodeKind]string{
		NodeModule:             "module",
		NodePackage:            "package",
		NodeClass:              "class",
		NodeMethod:             "method",
		NodeEndpoint:           "endpoint",
		NodeEntity:             "entity",
		NodeRepository:         "repository",
		NodeQuery:              "query",
		NodeMigration:          "migration",
		NodeTopic:              "topic",
		NodeQueue:              "queue",
		NodeEvent:              "event",
		NodeRMIInterface:       "rmi_interface",
		NodeConfigFile:         "config_file",
		NodeConfigKey:          "config_key",
		NodeWebSocketEndpoint:  "websocket_endpoint",
		NodeInterface:          "interface",
		NodeAbstractClass:      "abstract_class",
		NodeEnum:               "enum",
		NodeAnnotationType:     "annotation_type",
		NodeProtocolMessage:    "protocol_message",
		NodeConfigDefinition:   "config_definition",
		NodeDatabaseConnection: "database_connection",
		NodeAzureResource:      "azure_resource",
		NodeAzureFunction:      "azure_function",
		NodeMessageQueue:       "message_queue",
		NodeInfraResource:      "infra_resource",
		NodeComponent:          "component",
		NodeGuard:              "guard",
		NodeMiddleware:         "middleware",
		NodeHook:               "hook",
		NodeService:            "service",
		NodeExternal:           "external",
		NodeSQLEntity:          "sql_entity",
	}
	for kind, want := range cases {
		if got := kind.String(); got != want {
			t.Errorf("%v.String() = %q, want %q", kind, got, want)
		}
	}
}

func TestNodeKindFromString(t *testing.T) {
	for _, k := range AllNodeKinds() {
		got, err := ParseNodeKind(k.String())
		if err != nil {
			t.Errorf("ParseNodeKind(%q) error = %v", k.String(), err)
			continue
		}
		if got != k {
			t.Errorf("round-trip: ParseNodeKind(%q) = %v, want %v", k.String(), got, k)
		}
	}
	if _, err := ParseNodeKind("not_a_kind"); err == nil {
		t.Error("ParseNodeKind(\"not_a_kind\") err = nil, want non-nil")
	}
}

func TestNodeKindJSON(t *testing.T) {
	b, err := json.Marshal(NodeRMIInterface)
	if err != nil {
		t.Fatal(err)
	}
	if string(b) != `"rmi_interface"` {
		t.Fatalf("Marshal = %s, want %q", b, `"rmi_interface"`)
	}
	var k NodeKind
	if err := json.Unmarshal([]byte(`"endpoint"`), &k); err != nil {
		t.Fatal(err)
	}
	if k != NodeEndpoint {
		t.Fatalf("Unmarshal = %v, want NodeEndpoint", k)
	}
}
