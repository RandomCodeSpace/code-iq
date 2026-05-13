package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// KafkaProtocolDetector mirrors Java KafkaProtocolDetector: classes that
// extend AbstractRequest / AbstractResponse become PROTOCOL_MESSAGE nodes
// with EXTENDS edges.
type KafkaProtocolDetector struct{}

func NewKafkaProtocolDetector() *KafkaProtocolDetector { return &KafkaProtocolDetector{} }

func (KafkaProtocolDetector) Name() string                 { return "kafka_protocol" }
func (KafkaProtocolDetector) SupportedLanguages() []string { return []string{"java"} }
func (KafkaProtocolDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewKafkaProtocolDetector()) }

// `(?!\.)` (negative lookahead) is not supported by Go's RE2 — the original
// regex `extends\s+(AbstractRequest|AbstractResponse)(?!\.)\b` rejects matches
// where the parent has a `.` immediately after (e.g. `AbstractRequest.Builder`).
// We approximate by capturing the next char and rejecting `.` in code.
var kafkaProtoRE = regexp.MustCompile(`class\s+(\w+)\s+extends\s+(AbstractRequest|AbstractResponse)`)

func (d KafkaProtocolDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "AbstractRequest") && !strings.Contains(text, "AbstractResponse") {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	for i, line := range lines {
		m := kafkaProtoRE.FindStringSubmatchIndex(line)
		if m == nil {
			continue
		}
		// Reject the (?!\.) — if the match end is followed by `.`, skip.
		if m[5] < len(line) && line[m[5]] == '.' {
			continue
		}
		className := line[m[2]:m[3]]
		parent := line[m[4]:m[5]]
		protocolType := "request"
		if parent == "AbstractResponse" {
			protocolType = "response"
		}
		nodeID := ctx.FilePath + ":" + className
		n := model.NewCodeNode(nodeID, model.NodeProtocolMessage, className)
		n.FilePath = ctx.FilePath
		n.LineStart = i + 1
		n.Source = "KafkaProtocolDetector"
		n.Properties["protocol_type"] = protocolType
		nodes = append(nodes, n)

		e := model.NewCodeEdge(nodeID+"->extends->*:"+parent, model.EdgeExtends, nodeID, "*:"+parent)
		edges = append(edges, e)
	}

	// Use base.FindLineNumber for consistency (we already have per-line index here so this is unnecessary)
	_ = base.FindLineNumber
	return detector.ResultOf(nodes, edges)
}
