// Package proto holds Protocol Buffer detectors.
package proto

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// StructureDetector detects Protocol Buffer packages, imports, services,
// RPCs, and messages. Mirrors Java ProtoStructureDetector.
type StructureDetector struct{}

func NewStructureDetector() *StructureDetector { return &StructureDetector{} }

func (StructureDetector) Name() string                        { return "proto_structure" }
func (StructureDetector) SupportedLanguages() []string        { return []string{"proto"} }
func (StructureDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewStructureDetector()) }

var (
	protoServiceRE = regexp.MustCompile(`service\s+(\w+)\s*\{`)
	protoRpcRE     = regexp.MustCompile(`rpc\s+(\w+)\s*\((\w+)\)\s*returns\s*\((\w+)\)`)
	protoMessageRE = regexp.MustCompile(`message\s+(\w+)\s*\{`)
	protoImportRE  = regexp.MustCompile(`import\s+"([^"]+)"`)
	protoPackageRE = regexp.MustCompile(`package\s+([\w.]+)\s*;`)
)

func (d StructureDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath
	lines := strings.Split(text, "\n")
	seen := map[string]bool{}

	// Package (first match only)
	for i, line := range lines {
		if m := protoPackageRE.FindStringSubmatch(line); len(m) >= 2 {
			pkg := m[1]
			n := model.NewCodeNode("proto:"+fp+":package:"+pkg, model.NodeConfigKey, "package "+pkg)
			n.FQN = pkg
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "ProtoStructureDetector"
			n.Properties["package"] = pkg
			nodes = append(nodes, n)
			break
		}
	}

	// Imports — emit anchor nodes so the imports edge survives GraphBuilder's
	// phantom-drop filter. Without anchors, fp and imp are free-form strings
	// that don't match any CodeNode.
	for _, line := range lines {
		if m := protoImportRE.FindStringSubmatch(line); len(m) >= 2 {
			imp := m[1]
			srcID := base.EnsureFileAnchor(ctx, "proto", "ProtoStructureDetector", model.ConfidenceLexical, &nodes, seen)
			tgtID := base.EnsureExternalAnchor(imp, "proto:external", "ProtoStructureDetector", model.ConfidenceLexical, &nodes, seen)
			e := model.NewCodeEdge(srcID+":imports:"+tgtID, model.EdgeImports, srcID, tgtID)
			e.Source = "ProtoStructureDetector"
			edges = append(edges, e)
		}
	}

	// Services + RPCs (track current service via brace depth)
	currentService := ""
	braceDepth := 0
	for i, line := range lines {
		// Service start
		if m := protoServiceRE.FindStringSubmatch(line); len(m) >= 2 {
			svcName := m[1]
			currentService = svcName
			braceDepth = 0
			n := model.NewCodeNode("proto:"+fp+":service:"+svcName, model.NodeInterface, svcName)
			n.FQN = svcName
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "ProtoStructureDetector"
			nodes = append(nodes, n)
		}

		// Track brace depth to detect end of service block
		if currentService != "" {
			for _, c := range line {
				switch c {
				case '{':
					braceDepth++
				case '}':
					braceDepth--
				}
			}
			if braceDepth <= 0 {
				currentService = ""
			}
		}

		// RPC
		if m := protoRpcRE.FindStringSubmatch(line); len(m) >= 4 {
			methodName := m[1]
			reqType := m[2]
			respType := m[3]
			svc := currentService
			if svc == "" {
				svc = "_unknown"
			}
			rpcID := "proto:" + fp + ":rpc:" + svc + ":" + methodName
			n := model.NewCodeNode(rpcID, model.NodeMethod, svc+"."+methodName)
			n.FQN = svc + "." + methodName
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "ProtoStructureDetector"
			n.Properties["request_type"] = reqType
			n.Properties["response_type"] = respType
			nodes = append(nodes, n)

			if currentService != "" {
				svcID := "proto:" + fp + ":service:" + currentService
				e := model.NewCodeEdge(
					svcID+":contains:"+rpcID, model.EdgeContains, svcID, rpcID,
				)
				e.Source = "ProtoStructureDetector"
				edges = append(edges, e)
			}
		}
	}

	// Messages
	for i, line := range lines {
		if m := protoMessageRE.FindStringSubmatch(line); len(m) >= 2 {
			name := m[1]
			n := model.NewCodeNode("proto:"+fp+":message:"+name, model.NodeProtocolMessage, name)
			n.FQN = name
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "ProtoStructureDetector"
			nodes = append(nodes, n)
		}
	}

	return detector.ResultOf(nodes, edges)
}
