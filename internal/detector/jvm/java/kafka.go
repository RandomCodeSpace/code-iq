package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// KafkaDetector mirrors Java KafkaDetector. Detects @KafkaListener consumers
// and KafkaTemplate.send() producers across Java + Kotlin.
type KafkaDetector struct{}

func NewKafkaDetector() *KafkaDetector { return &KafkaDetector{} }

func (KafkaDetector) Name() string                 { return "kafka" }
func (KafkaDetector) SupportedLanguages() []string { return []string{"java", "kotlin"} }
func (KafkaDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewKafkaDetector()) }

var (
	// Kotlin class/object modifiers + Java class.
	kafkaClassRE = regexp.MustCompile(
		`(?:(?:public|internal|private|protected|data|abstract|open|sealed|enum|inline|value)\s+)*(?:class|object)\s+(\w+)`,
	)
	// `@KafkaListener("orders")` or `@KafkaListener(topics = "orders", ...)`. Java's
	// `[\{"]?` allows opening `{` for arrays.
	kafkaListenerRE = regexp.MustCompile(`@KafkaListener\s*\(\s*(?:.*?topics?\s*=\s*)?[{"]?\s*"([^"]+)"`)
	kafkaSendRE     = regexp.MustCompile(`(?:kafkaTemplate|KafkaTemplate)\s*\.send\s*\(\s*"([^"]+)"`)
	kafkaGroupRE    = regexp.MustCompile(`groupId\s*=\s*"([^"]+)"`)
	kafkaQuotedRE   = regexp.MustCompile(`"([^"]+)"`)
)

func (d KafkaDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "KafkaListener") &&
		!strings.Contains(text, "KafkaTemplate") &&
		!strings.Contains(text, "kafkaTemplate") {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	var className string
	for _, line := range lines {
		if m := kafkaClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			break
		}
	}
	if className == "" {
		return detector.EmptyResult()
	}
	classNodeID := ctx.FilePath + ":" + className
	seenTopics := map[string]bool{}

	// @KafkaListener consumers
	for i, line := range lines {
		m := kafkaListenerRE.FindStringSubmatch(line)
		if m == nil {
			// fallback for `@KafkaListener` annotation that wraps onto next line
			if i > 0 && strings.Contains(lines[i-1], "@KafkaListener") {
				if fb := kafkaQuotedRE.FindStringSubmatch(line); fb != nil {
					topic := fb[1]
					topicID := ensureKafkaTopic(topic, seenTopics, &nodes)
					props := map[string]any{"topic": topic}
					addKafkaEdge(classNodeID, topicID, model.EdgeConsumes,
						className+" consumes "+topic, props, &edges)
				}
			}
			continue
		}
		topic := m[1]
		topicID := ensureKafkaTopic(topic, seenTopics, &nodes)
		props := map[string]any{"topic": topic}
		if gm := kafkaGroupRE.FindStringSubmatch(line); gm != nil {
			props["group_id"] = gm[1]
		}
		addKafkaEdge(classNodeID, topicID, model.EdgeConsumes,
			className+" consumes "+topic, props, &edges)
	}

	// KafkaTemplate.send producers
	for _, line := range lines {
		m := kafkaSendRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		topic := m[1]
		topicID := ensureKafkaTopic(topic, seenTopics, &nodes)
		addKafkaEdge(classNodeID, topicID, model.EdgeProduces,
			className+" produces to "+topic, map[string]any{"topic": topic}, &edges)
	}

	return detector.ResultOf(nodes, edges)
}

func ensureKafkaTopic(topic string, seen map[string]bool, nodes *[]*model.CodeNode) string {
	topicID := "kafka:topic:" + topic
	if !seen[topic] {
		seen[topic] = true
		n := model.NewCodeNode(topicID, model.NodeTopic, "kafka:"+topic)
		n.Source = "KafkaDetector"
		n.Properties["broker"] = "kafka"
		n.Properties["topic"] = topic
		*nodes = append(*nodes, n)
	}
	return topicID
}

func addKafkaEdge(sourceID, targetID string, kind model.EdgeKind, _ string, props map[string]any, edges *[]*model.CodeEdge) {
	e := model.NewCodeEdge(sourceID+"->"+kind.String()+"->"+targetID, kind, sourceID, targetID)
	for k, v := range props {
		e.Properties[k] = v
	}
	*edges = append(*edges, e)
}
