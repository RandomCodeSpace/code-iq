package python

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// KafkaPythonDetector ports
// io.github.randomcodespace.iq.detector.python.KafkaPythonDetector.
type KafkaPythonDetector struct{}

func NewKafkaPythonDetector() *KafkaPythonDetector { return &KafkaPythonDetector{} }

func (KafkaPythonDetector) Name() string                        { return "kafka_python" }
func (KafkaPythonDetector) SupportedLanguages() []string        { return []string{"python"} }
func (KafkaPythonDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewKafkaPythonDetector()) }

var (
	kpyProducerRE         = regexp.MustCompile(`(KafkaProducer|AIOKafkaProducer)\s*\(`)
	kpyConfluentProducerRE = regexp.MustCompile(`Producer\s*\(\s*\{`)
	kpyConsumerRE         = regexp.MustCompile(`(KafkaConsumer|AIOKafkaConsumer)\s*\(`)
	kpyConfluentConsumerRE = regexp.MustCompile(`Consumer\s*\(\s*\{`)
	kpySendRE             = regexp.MustCompile(`\.send\s*\(\s*['"]([^'"]+)['"]`)
	kpyProduceRE          = regexp.MustCompile(`\.produce\s*\(\s*['"]([^'"]+)['"]`)
	kpySubscribeRE        = regexp.MustCompile(`\.subscribe\s*\(\s*\[\s*['"]([^'"]+)['"]`)
	kpyImportRE           = regexp.MustCompile(`(?:from|import)\s+(confluent_kafka|kafka|aiokafka)\b`)
)

var kafkaKeywords = []string{
	"KafkaProducer", "KafkaConsumer",
	"AIOKafkaProducer", "AIOKafkaConsumer",
	"confluent_kafka", "from kafka",
	"import kafka", "Producer(", "Consumer(",
}

func (d KafkaPythonDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	hasKafka := false
	for _, kw := range kafkaKeywords {
		if strings.Contains(text, kw) {
			hasKafka = true
			break
		}
	}
	if !hasKafka {
		return detector.EmptyResult()
	}

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName
	seenTopics := make(map[string]bool)
	fileNodeID := "kafka_py:" + filePath

	ensureTopic := func(topic, role string, lineno int) string {
		topicID := "kafka_py:" + filePath + ":topic:" + topic
		if !seenTopics[topic] {
			seenTopics[topic] = true
			n := model.NewCodeNode(topicID, model.NodeTopic, "kafka:"+topic)
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "KafkaPythonDetector"
			n.Confidence = model.ConfidenceLexical
			n.Properties["broker"] = "kafka"
			n.Properties["topic"] = topic
			n.Properties["role"] = role
			nodes = append(nodes, n)
		}
		return topicID
	}

	lines := strings.Split(text, "\n")
	for i, line := range lines {
		lineno := i + 1
		if kpyProducerRE.MatchString(line) || kpyConfluentProducerRE.MatchString(line) {
			n := model.NewCodeNode(
				fmt.Sprintf("kafka_py:%s:producer:%d", filePath, lineno),
				model.NodeTopic, "kafka:producer",
			)
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "KafkaPythonDetector"
			n.Confidence = model.ConfidenceLexical
			n.Properties["role"] = "producer"
			nodes = append(nodes, n)
		}
	}

	for i, line := range lines {
		lineno := i + 1
		if kpyConsumerRE.MatchString(line) || kpyConfluentConsumerRE.MatchString(line) {
			n := model.NewCodeNode(
				fmt.Sprintf("kafka_py:%s:consumer:%d", filePath, lineno),
				model.NodeTopic, "kafka:consumer",
			)
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "KafkaPythonDetector"
			n.Confidence = model.ConfidenceLexical
			n.Properties["role"] = "consumer"
			nodes = append(nodes, n)
		}
	}

	for i, line := range lines {
		lineno := i + 1
		if sm := kpySendRE.FindStringSubmatch(line); sm != nil && strings.Contains(line, "send") {
			topic := sm[1]
			topicID := ensureTopic(topic, "producer", lineno)
			e := model.NewCodeEdge(fileNodeID+"->produces->"+topicID, model.EdgeProduces, fileNodeID, topicID)
			e.Source = "KafkaPythonDetector"
			e.Confidence = model.ConfidenceLexical
			e.Properties["topic"] = topic
			edges = append(edges, e)
			continue
		}
		if pm := kpyProduceRE.FindStringSubmatch(line); pm != nil {
			topic := pm[1]
			topicID := ensureTopic(topic, "producer", lineno)
			e := model.NewCodeEdge(fileNodeID+"->produces->"+topicID, model.EdgeProduces, fileNodeID, topicID)
			e.Source = "KafkaPythonDetector"
			e.Confidence = model.ConfidenceLexical
			e.Properties["topic"] = topic
			edges = append(edges, e)
		}
	}

	for i, line := range lines {
		lineno := i + 1
		if subm := kpySubscribeRE.FindStringSubmatch(line); subm != nil {
			topic := subm[1]
			topicID := ensureTopic(topic, "consumer", lineno)
			e := model.NewCodeEdge(fileNodeID+"->consumes->"+topicID, model.EdgeConsumes, fileNodeID, topicID)
			e.Source = "KafkaPythonDetector"
			e.Confidence = model.ConfidenceLexical
			e.Properties["topic"] = topic
			edges = append(edges, e)
		}
	}

	for _, line := range lines {
		if im := kpyImportRE.FindStringSubmatch(line); im != nil {
			lib := im[1]
			e := model.NewCodeEdge(
				fileNodeID+"->imports->kafka_py:lib:"+lib,
				model.EdgeImports, fileNodeID, "kafka_py:lib:"+lib,
			)
			e.Source = "KafkaPythonDetector"
			e.Confidence = model.ConfidenceLexical
			e.Properties["library"] = lib
			edges = append(edges, e)
		}
	}

	return detector.ResultOf(nodes, edges)
}
