package model

import (
	"encoding/json"
	"fmt"
)

// NodeKind enumerates the 34 node types in the codeiq graph.
// String values MUST match the Java NodeKind enum 1:1 (see
// src/main/java/io/github/randomcodespace/iq/model/NodeKind.java).
type NodeKind int

const (
	NodeModule NodeKind = iota
	NodePackage
	NodeClass
	NodeMethod
	NodeEndpoint
	NodeEntity
	NodeRepository
	NodeQuery
	NodeMigration
	NodeTopic
	NodeQueue
	NodeEvent
	NodeRMIInterface
	NodeConfigFile
	NodeConfigKey
	NodeWebSocketEndpoint
	NodeInterface
	NodeAbstractClass
	NodeEnum
	NodeAnnotationType
	NodeProtocolMessage
	NodeConfigDefinition
	NodeDatabaseConnection
	NodeAzureResource
	NodeAzureFunction
	NodeMessageQueue
	NodeInfraResource
	NodeComponent
	NodeGuard
	NodeMiddleware
	NodeHook
	NodeService
	NodeExternal
	NodeSQLEntity
)

var nodeKindNames = [...]string{
	"module",
	"package",
	"class",
	"method",
	"endpoint",
	"entity",
	"repository",
	"query",
	"migration",
	"topic",
	"queue",
	"event",
	"rmi_interface",
	"config_file",
	"config_key",
	"websocket_endpoint",
	"interface",
	"abstract_class",
	"enum",
	"annotation_type",
	"protocol_message",
	"config_definition",
	"database_connection",
	"azure_resource",
	"azure_function",
	"message_queue",
	"infra_resource",
	"component",
	"guard",
	"middleware",
	"hook",
	"service",
	"external",
	"sql_entity",
}

// String returns the canonical lowercase value.
func (k NodeKind) String() string {
	if int(k) < 0 || int(k) >= len(nodeKindNames) {
		return fmt.Sprintf("nodekind(%d)", int(k))
	}
	return nodeKindNames[k]
}

// AllNodeKinds returns every NodeKind in declaration order.
func AllNodeKinds() []NodeKind {
	out := make([]NodeKind, len(nodeKindNames))
	for i := range nodeKindNames {
		out[i] = NodeKind(i)
	}
	return out
}

// ParseNodeKind looks up a NodeKind by its canonical string value.
func ParseNodeKind(s string) (NodeKind, error) {
	for i, name := range nodeKindNames {
		if name == s {
			return NodeKind(i), nil
		}
	}
	return 0, fmt.Errorf("unknown NodeKind: %q", s)
}

// MarshalJSON emits the canonical string value.
func (k NodeKind) MarshalJSON() ([]byte, error) {
	return json.Marshal(k.String())
}

// UnmarshalJSON parses the canonical string value.
func (k *NodeKind) UnmarshalJSON(data []byte) error {
	var s string
	if err := json.Unmarshal(data, &s); err != nil {
		return err
	}
	parsed, err := ParseNodeKind(s)
	if err != nil {
		return err
	}
	*k = parsed
	return nil
}
