package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// GrpcServiceDetector mirrors Java GrpcServiceDetector. Detects gRPC service
// implementations (XxxGrpc.XxxImplBase) and client stubs (XxxGrpc.newStub).
type GrpcServiceDetector struct{}

func NewGrpcServiceDetector() *GrpcServiceDetector { return &GrpcServiceDetector{} }

func (GrpcServiceDetector) Name() string { return "grpc_service" }
func (GrpcServiceDetector) SupportedLanguages() []string {
	return []string{"java"}
}
func (GrpcServiceDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewGrpcServiceDetector()) }

var (
	grpcClassRE     = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	grpcImplRE      = regexp.MustCompile(`class\s+(\w+)\s+extends\s+(\w+)Grpc\.(\w+)ImplBase`)
	grpcMethodRE    = regexp.MustCompile(`public\s+[\w<>\[\]]+\s+(\w+)\s*\(\s*(\w+)`)
	grpcStubRE      = regexp.MustCompile(`(\w+)Grpc\.new(?:Blocking|Future)?Stub\s*\(`)
)

func (GrpcServiceDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	hasImpl := strings.Contains(text, "ImplBase") || strings.Contains(text, "@GrpcService")
	hasStub := strings.Contains(text, "Grpc.new")
	if !hasImpl && !hasStub {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	className := ""
	classLine := 0
	for i, line := range lines {
		if m := grpcClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			classLine = i + 1
			break
		}
	}
	if className == "" {
		return detector.EmptyResult()
	}
	classID := ctx.FilePath + ":" + className

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	if m := grpcImplRE.FindStringSubmatch(text); m != nil {
		serviceProto := m[2]
		serviceID := "grpc:service:" + serviceProto
		sn := model.NewCodeNode(serviceID, model.NodeEndpoint, "gRPC "+serviceProto)
		sn.FQN = className + " (" + serviceProto + ")"
		sn.FilePath = ctx.FilePath
		sn.LineStart = classLine
		sn.Source = "GrpcServiceDetector"
		sn.Confidence = base.RegexDetectorDefaultConfidence
		if strings.Contains(text, "@GrpcService") {
			sn.Annotations = append(sn.Annotations, "@GrpcService")
		}
		sn.Properties["protocol"] = "grpc"
		sn.Properties["service"] = serviceProto
		sn.Properties["implementation"] = className
		nodes = append(nodes, sn)
		edges = append(edges, model.NewCodeEdge(classID+"->exposes->"+serviceID, model.EdgeExposes, classID, serviceID))

		for i, line := range lines {
			if !strings.Contains(line, "@Override") {
				continue
			}
			end := i + 3
			if end > len(lines) {
				end = len(lines)
			}
			for k := i + 1; k < end; k++ {
				if mm := grpcMethodRE.FindStringSubmatch(lines[k]); mm != nil {
					methodName := mm[1]
					rpcID := "grpc:rpc:" + serviceProto + "/" + methodName
					rn := model.NewCodeNode(rpcID, model.NodeEndpoint, "gRPC "+serviceProto+"/"+methodName)
					rn.FQN = className + "." + methodName
					rn.FilePath = ctx.FilePath
					rn.LineStart = k + 1
					rn.Source = "GrpcServiceDetector"
					rn.Confidence = base.RegexDetectorDefaultConfidence
					rn.Properties["protocol"] = "grpc"
					rn.Properties["service"] = serviceProto
					rn.Properties["method"] = methodName
					nodes = append(nodes, rn)
					break
				}
			}
		}
	}

	for _, m := range grpcStubRE.FindAllStringSubmatch(text, -1) {
		target := m[1]
		targetID := "grpc:service:" + target
		e := model.NewCodeEdge(classID+"->calls->"+targetID, model.EdgeCalls, classID, targetID)
		e.Properties["protocol"] = "grpc"
		e.Properties["target_service"] = target
		edges = append(edges, e)
	}

	return detector.ResultOf(nodes, edges)
}
