package typescript

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// KafkaJSDetector ports
// io.github.randomcodespace.iq.detector.typescript.KafkaJSDetector.
type KafkaJSDetector struct{}

func NewKafkaJSDetector() *KafkaJSDetector { return &KafkaJSDetector{} }

func (KafkaJSDetector) Name() string                 { return "kafka_js" }
func (KafkaJSDetector) SupportedLanguages() []string { return []string{"typescript", "javascript"} }
func (KafkaJSDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewKafkaJSDetector()) }

var (
	kjsNewKafkaRE     = regexp.MustCompile(`new\s+Kafka\s*\(\s*\{`)
	kjsProducerRE     = regexp.MustCompile(`\.producer\s*\(\s*\)`)
	kjsProducerSendRE = regexp.MustCompile(`\.send\s*\(\s*\{\s*topic\s*:\s*['"]([^'"]+)['"]`)
	kjsConsumerRE     = regexp.MustCompile(`\.consumer\s*\(\s*\{\s*groupId\s*:\s*['"]([^'"]+)['"]`)
	kjsSubscribeRE    = regexp.MustCompile(`\.subscribe\s*\(\s*\{\s*topic\s*:\s*['"]([^'"]+)['"]`)
	kjsRunEachRE      = regexp.MustCompile(`\.run\s*\(\s*\{\s*eachMessage\s*:`)
)

func (d KafkaJSDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if !strings.Contains(text, "Kafka") && !strings.Contains(text, "kafka") {
		return detector.EmptyResult()
	}

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName
	seenTopics := make(map[string]bool)
	fileNodeID := "kafka_js:" + filePath

	lines := strings.Split(text, "\n")
	ensureTopic := func(topic string, lineno int) string {
		topicID := "kafka_js:" + filePath + ":topic:" + topic
		if !seenTopics[topic] {
			seenTopics[topic] = true
			n := model.NewCodeNode(topicID, model.NodeTopic, "kafka:"+topic)
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "KafkaJSDetector"
			n.Confidence = model.ConfidenceLexical
			n.Properties["broker"] = "kafka"
			n.Properties["topic"] = topic
			nodes = append(nodes, n)
		}
		return topicID
	}

	for i, line := range lines {
		lineno := i + 1

		if kjsNewKafkaRE.MatchString(line) {
			n := model.NewCodeNode(
				fmt.Sprintf("kafka_js:%s:connection:%d", filePath, lineno),
				model.NodeDatabaseConnection, "KafkaJS connection",
			)
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "KafkaJSDetector"
			n.Confidence = model.ConfidenceLexical
			n.Properties["broker"] = "kafka"
			n.Properties["library"] = "kafkajs"
			nodes = append(nodes, n)
		}

		if kjsProducerRE.MatchString(line) {
			n := model.NewCodeNode(
				fmt.Sprintf("kafka_js:%s:producer:%d", filePath, lineno),
				model.NodeTopic, "kafka:producer",
			)
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "KafkaJSDetector"
			n.Confidence = model.ConfidenceLexical
			n.Properties["role"] = "producer"
			nodes = append(nodes, n)
		}

		if sm := kjsProducerSendRE.FindStringSubmatch(line); sm != nil {
			topic := sm[1]
			topicID := ensureTopic(topic, lineno)
			e := model.NewCodeEdge(fileNodeID+"->produces->"+topicID, model.EdgeProduces, fileNodeID, topicID)
			e.Source = "KafkaJSDetector"
			e.Confidence = model.ConfidenceLexical
			e.Properties["topic"] = topic
			edges = append(edges, e)
		}

		if sm := kjsConsumerRE.FindStringSubmatch(line); sm != nil {
			groupID := sm[1]
			n := model.NewCodeNode(
				fmt.Sprintf("kafka_js:%s:consumer:%d", filePath, lineno),
				model.NodeTopic, "kafka:consumer:"+groupID,
			)
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "KafkaJSDetector"
			n.Confidence = model.ConfidenceLexical
			n.Properties["role"] = "consumer"
			n.Properties["group_id"] = groupID
			nodes = append(nodes, n)
		}

		if sm := kjsSubscribeRE.FindStringSubmatch(line); sm != nil {
			topic := sm[1]
			topicID := ensureTopic(topic, lineno)
			e := model.NewCodeEdge(fileNodeID+"->consumes->"+topicID, model.EdgeConsumes, fileNodeID, topicID)
			e.Source = "KafkaJSDetector"
			e.Confidence = model.ConfidenceLexical
			e.Properties["topic"] = topic
			edges = append(edges, e)
		}

		if kjsRunEachRE.MatchString(line) {
			n := model.NewCodeNode(
				fmt.Sprintf("kafka_js:%s:event:%d", filePath, lineno),
				model.NodeEvent, "kafka:eachMessage",
			)
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "KafkaJSDetector"
			n.Confidence = model.ConfidenceLexical
			n.Properties["handler"] = "eachMessage"
			nodes = append(nodes, n)
		}
	}

	return detector.ResultOf(nodes, edges)
}
