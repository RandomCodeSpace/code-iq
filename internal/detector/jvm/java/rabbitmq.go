package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/detector/jvm/jvmhelpers"
	"github.com/randomcodespace/codeiq/internal/model"
)

// RabbitmqDetector mirrors Java RabbitmqDetector.
type RabbitmqDetector struct{}

func NewRabbitmqDetector() *RabbitmqDetector { return &RabbitmqDetector{} }

func (RabbitmqDetector) Name() string                 { return "rabbitmq" }
func (RabbitmqDetector) SupportedLanguages() []string { return []string{"java"} }
func (RabbitmqDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewRabbitmqDetector()) }

var (
	rabbitListenerRE  = regexp.MustCompile(`@RabbitListener\s*\(\s*(?:.*?queues?\s*=\s*)?[{"]?\s*"([^"]+)"`)
	rabbitSendRE      = regexp.MustCompile(`(?:rabbitTemplate|RabbitTemplate)\s*\.(?:convertAndSend|send)\s*\(\s*"([^"]+)"`)
	rabbitExchangeRE  = regexp.MustCompile(`(?:DirectExchange|TopicExchange|FanoutExchange|HeadersExchange)\s*\(\s*"([^"]+)"`)
	rabbitRoutingKeyRE = regexp.MustCompile(`routingKey\s*=\s*"([^"]+)"`)
)

func (d RabbitmqDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "@RabbitListener") && !strings.Contains(text, "RabbitTemplate") &&
		!strings.Contains(text, "rabbitTemplate") && !strings.Contains(text, "DirectExchange") &&
		!strings.Contains(text, "TopicExchange") && !strings.Contains(text, "FanoutExchange") {
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
		m := rabbitListenerRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		queue := m[1]
		queueID := ensureRabbitQueueNode(queue, seenQueues, &nodes)
		edges = jvmhelpers.AddMessagingEdge(classNodeID, queueID, model.EdgeConsumes,
			queue, map[string]any{"queue": queue}, edges)
	}

	for _, line := range lines {
		m := rabbitSendRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		exchangeOrQueue := m[1]
		props := map[string]any{"exchange": exchangeOrQueue}
		if rk := rabbitRoutingKeyRE.FindStringSubmatch(line); rk != nil {
			props["routing_key"] = rk[1]
		}
		queueID := "rabbitmq:exchange:" + exchangeOrQueue
		if !seenQueues[exchangeOrQueue] {
			seenQueues[exchangeOrQueue] = true
			n := model.NewCodeNode(queueID, model.NodeQueue, "rabbitmq:"+exchangeOrQueue)
			n.Source = "RabbitmqDetector"
			n.Properties["broker"] = "rabbitmq"
			n.Properties["exchange"] = exchangeOrQueue
			nodes = append(nodes, n)
		}
		edges = jvmhelpers.AddMessagingEdge(classNodeID, queueID, model.EdgeProduces,
			exchangeOrQueue, props, edges)
	}

	for _, m := range rabbitExchangeRE.FindAllStringSubmatchIndex(text, -1) {
		exchangeName := text[m[2]:m[3]]
		lineNum := base.FindLineNumber(text, m[0])
		exchangeID := "rabbitmq:exchange:" + exchangeName
		if !seenQueues[exchangeName] {
			seenQueues[exchangeName] = true
			n := model.NewCodeNode(exchangeID, model.NodeQueue, "rabbitmq:exchange:"+exchangeName)
			n.FilePath = ctx.FilePath
			n.LineStart = lineNum
			n.Source = "RabbitmqDetector"
			n.Properties["broker"] = "rabbitmq"
			n.Properties["exchange"] = exchangeName
			nodes = append(nodes, n)
		}
	}

	return detector.ResultOf(nodes, edges)
}

func ensureRabbitQueueNode(queue string, seen map[string]bool, nodes *[]*model.CodeNode) string {
	queueID := "rabbitmq:queue:" + queue
	if !seen[queue] {
		seen[queue] = true
		n := model.NewCodeNode(queueID, model.NodeQueue, "rabbitmq:"+queue)
		n.Source = "RabbitmqDetector"
		n.Properties["broker"] = "rabbitmq"
		n.Properties["queue"] = queue
		*nodes = append(*nodes, n)
	}
	return queueID
}
