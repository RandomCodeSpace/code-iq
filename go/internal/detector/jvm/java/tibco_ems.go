package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/detector/jvm/jvmhelpers"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// TibcoEmsDetector mirrors Java TibcoEmsDetector.
type TibcoEmsDetector struct{}

func NewTibcoEmsDetector() *TibcoEmsDetector { return &TibcoEmsDetector{} }

func (TibcoEmsDetector) Name() string                 { return "tibco_ems" }
func (TibcoEmsDetector) SupportedLanguages() []string { return []string{"java"} }
func (TibcoEmsDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewTibcoEmsDetector()) }

var (
	tibFactoryRE     = regexp.MustCompile(`\b(TibjmsConnectionFactory|TibjmsQueueConnectionFactory|TibjmsTopicConnectionFactory)\b`)
	tibServerURLRE   = regexp.MustCompile(`"(tcp://[^"]+)"`)
	tibCreateQueueRE = regexp.MustCompile(`createQueue\s*\(\s*"([^"]+)"`)
	tibCreateTopicRE = regexp.MustCompile(`createTopic\s*\(\s*"([^"]+)"`)
	tibSendRE        = regexp.MustCompile(`\bsend\s*\(`)
	tibPublishRE     = regexp.MustCompile(`\bpublish\s*\(`)
	tibReceiveRE     = regexp.MustCompile(`\breceive\s*\(`)
	tibOnMessageRE   = regexp.MustCompile(`\bonMessage\s*\(`)
	tibProducerRE    = regexp.MustCompile(`\bMessageProducer\b`)
	tibConsumerRE    = regexp.MustCompile(`\bMessageConsumer\b`)
	tibQueueRE       = regexp.MustCompile(`new\s+TibjmsQueue\s*\(\s*"([^"]+)"`)
	tibTopicRE       = regexp.MustCompile(`new\s+TibjmsTopic\s*\(\s*"([^"]+)"`)
)

func (d TibcoEmsDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "tibjms") && !strings.Contains(text, "TibjmsConnectionFactory") &&
		!strings.Contains(text, "com.tibco") && !strings.Contains(text, "TIBJMS") {
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
	seenTopics := map[string]bool{}

	isProducer := tibSendRE.MatchString(text) || tibPublishRE.MatchString(text) || tibProducerRE.MatchString(text)
	isConsumer := tibReceiveRE.MatchString(text) || tibOnMessageRE.MatchString(text) || tibConsumerRE.MatchString(text)

	// Connection factory
	for i, line := range lines {
		m := tibFactoryRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		factoryType := m[1]
		var serverURL string
		startWindow := max0(i - 1)
		endWindow := min0(len(lines), i+4)
		for j := startWindow; j < endWindow; j++ {
			if urlM := tibServerURLRE.FindStringSubmatch(lines[j]); urlM != nil {
				serverURL = urlM[1]
				break
			}
		}
		nodeID := "ems:server:" + factoryType
		n := model.NewCodeNode(nodeID, model.NodeMessageQueue, "ems:"+factoryType)
		n.Source = "TibcoEmsDetector"
		n.Properties["broker"] = "tibco_ems"
		n.Properties["factory_type"] = factoryType
		if serverURL != "" {
			n.Properties["server_url"] = serverURL
		}
		nodes = append(nodes, n)
		edges = jvmhelpers.AddMessagingEdge(classNodeID, nodeID, model.EdgeConnectsTo,
			"", map[string]any{"factory_type": factoryType}, edges)
	}

	// createQueue / createTopic
	for _, line := range lines {
		if m := tibCreateQueueRE.FindStringSubmatch(line); m != nil {
			queueName := m[1]
			queueID := ensureTibcoQueue(queueName, seenQueues, &nodes)
			if isProducer {
				edges = jvmhelpers.AddMessagingEdge(classNodeID, queueID, model.EdgeSendsTo,
					"", map[string]any{"queue": queueName}, edges)
			}
			if isConsumer {
				edges = jvmhelpers.AddMessagingEdge(classNodeID, queueID, model.EdgeReceivesFrom,
					"", map[string]any{"queue": queueName}, edges)
			}
		}
		if m := tibCreateTopicRE.FindStringSubmatch(line); m != nil {
			topicName := m[1]
			topicID := ensureTibcoTopic(topicName, seenTopics, &nodes)
			if isProducer {
				edges = jvmhelpers.AddMessagingEdge(classNodeID, topicID, model.EdgeSendsTo,
					"", map[string]any{"topic": topicName}, edges)
			}
			if isConsumer {
				edges = jvmhelpers.AddMessagingEdge(classNodeID, topicID, model.EdgeReceivesFrom,
					"", map[string]any{"topic": topicName}, edges)
			}
		}
	}

	// Direct instantiation
	for _, line := range lines {
		if m := tibQueueRE.FindStringSubmatch(line); m != nil {
			ensureTibcoQueue(m[1], seenQueues, &nodes)
		}
		if m := tibTopicRE.FindStringSubmatch(line); m != nil {
			ensureTibcoTopic(m[1], seenTopics, &nodes)
		}
	}

	return detector.ResultOf(nodes, edges)
}

func ensureTibcoQueue(name string, seen map[string]bool, nodes *[]*model.CodeNode) string {
	id := "ems:queue:" + name
	if !seen[name] {
		seen[name] = true
		n := model.NewCodeNode(id, model.NodeQueue, "ems:queue:"+name)
		n.Source = "TibcoEmsDetector"
		n.Properties["broker"] = "tibco_ems"
		n.Properties["queue"] = name
		*nodes = append(*nodes, n)
	}
	return id
}

func ensureTibcoTopic(name string, seen map[string]bool, nodes *[]*model.CodeNode) string {
	id := "ems:topic:" + name
	if !seen[name] {
		seen[name] = true
		n := model.NewCodeNode(id, model.NodeTopic, "ems:topic:"+name)
		n.Source = "TibcoEmsDetector"
		n.Properties["broker"] = "tibco_ems"
		n.Properties["topic"] = name
		*nodes = append(*nodes, n)
	}
	return id
}
