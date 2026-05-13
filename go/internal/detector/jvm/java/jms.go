package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/detector/jvm/jvmhelpers"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// JmsDetector mirrors Java JmsDetector. Detects @JmsListener and JmsTemplate.send().
type JmsDetector struct{}

func NewJmsDetector() *JmsDetector { return &JmsDetector{} }

func (JmsDetector) Name() string                        { return "jms" }
func (JmsDetector) SupportedLanguages() []string        { return []string{"java"} }
func (JmsDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewJmsDetector()) }

var (
	jmsListenerRE         = regexp.MustCompile(`@JmsListener\s*\(\s*(?:.*?destination\s*=\s*)?"([^"]+)"`)
	jmsSendRE             = regexp.MustCompile(`(?:jmsTemplate|JmsTemplate)\s*\.(?:send|convertAndSend)\s*\(\s*"([^"]+)"`)
	jmsContainerFactoryRE = regexp.MustCompile(`containerFactory\s*=\s*"([^"]+)"`)
)

func (d JmsDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "@JmsListener") && !strings.Contains(text, "jmsTemplate") &&
		!strings.Contains(text, "JmsTemplate") {
		return detector.EmptyResult()
	}

	className := jvmhelpers.ExtractClassName(text)
	if className == "" {
		return detector.EmptyResult()
	}
	classNodeID := ctx.FilePath + ":" + className

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	seenQueues := map[string]bool{}

	for _, line := range lines {
		m := jmsListenerRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		destination := m[1]
		queueID := ensureQueueNodeWithBroker("jms", destination, seenQueues, &nodes)
		props := map[string]any{"destination": destination}
		if cf := jmsContainerFactoryRE.FindStringSubmatch(line); cf != nil {
			props["container_factory"] = cf[1]
		}
		edges = jvmhelpers.AddMessagingEdge(classNodeID, queueID, model.EdgeConsumes,
			className+" consumes from "+destination, props, edges)
	}

	for _, line := range lines {
		m := jmsSendRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		destination := m[1]
		queueID := ensureQueueNodeWithBroker("jms", destination, seenQueues, &nodes)
		edges = jvmhelpers.AddMessagingEdge(classNodeID, queueID, model.EdgeProduces,
			className+" produces to "+destination, map[string]any{"destination": destination}, edges)
	}

	return detector.ResultOf(nodes, edges)
}

func ensureQueueNodeWithBroker(broker, destination string, seen map[string]bool, nodes *[]*model.CodeNode) string {
	queueID := broker + ":queue:" + destination
	if !seen[destination] {
		seen[destination] = true
		n := model.NewCodeNode(queueID, model.NodeQueue, broker+":"+destination)
		n.Source = "JmsDetector"
		n.Properties["broker"] = broker
		n.Properties["destination"] = destination
		*nodes = append(*nodes, n)
	}
	return queueID
}
