package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/detector/jvm/jvmhelpers"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// IbmMqDetector mirrors Java IbmMqDetector.
type IbmMqDetector struct{}

func NewIbmMqDetector() *IbmMqDetector { return &IbmMqDetector{} }

func (IbmMqDetector) Name() string                 { return "ibm_mq" }
func (IbmMqDetector) SupportedLanguages() []string { return []string{"java"} }
func (IbmMqDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewIbmMqDetector()) }

var (
	ibmQmNewRE         = regexp.MustCompile(`new\s+MQQueueManager\s*\(\s*"([^"]+)"`)
	ibmAccessQueueRE   = regexp.MustCompile(`accessQueue\s*\(\s*"([^"]+)"`)
	ibmMqTopicDeclRE   = regexp.MustCompile(`\bMQTopic\b`)
	ibmJmsCreateQueueRE = regexp.MustCompile(`createQueue\s*\(\s*"([^"]+)"`)
	ibmJmsCreateTopicRE = regexp.MustCompile(`createTopic\s*\(\s*"([^"]+)"`)
	ibmMqPutRE         = regexp.MustCompile(`\bput\s*\(`)
	ibmMqGetRE         = regexp.MustCompile(`\bget\s*\(`)
)

func (d IbmMqDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "MQQueueManager") && !strings.Contains(text, "JmsConnectionFactory") &&
		!strings.Contains(text, "com.ibm.mq") && !strings.Contains(text, "MQQueue") {
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

	hasPut := ibmMqPutRE.MatchString(text)
	hasGet := ibmMqGetRE.MatchString(text)

	seenQms := map[string]bool{}
	seenQueues := map[string]bool{}
	seenTopics := map[string]bool{}

	// MQQueueManager
	for _, line := range lines {
		if m := ibmQmNewRE.FindStringSubmatch(line); m != nil {
			qmName := m[1]
			qmID := ensureIbmNode("ibmmq:qm:"+qmName, qmName, model.NodeMessageQueue,
				"ibmmq:qm:"+qmName,
				map[string]any{"broker": "ibm_mq", "queue_manager": qmName},
				seenQms, &nodes)
			edges = jvmhelpers.AddMessagingEdge(classNodeID, qmID, model.EdgeConnectsTo,
				className+" connects to queue manager "+qmName,
				map[string]any{"queue_manager": qmName}, edges)
		}
	}

	// accessQueue
	for _, line := range lines {
		if m := ibmAccessQueueRE.FindStringSubmatch(line); m != nil {
			queueName := m[1]
			queueID := ensureIbmNode("ibmmq:queue:"+queueName, queueName, model.NodeQueue,
				"ibmmq:queue:"+queueName,
				map[string]any{"broker": "ibm_mq", "queue": queueName},
				seenQueues, &nodes)
			switch {
			case hasPut:
				edges = jvmhelpers.AddMessagingEdge(classNodeID, queueID, model.EdgeSendsTo,
					className+" sends to "+queueName, map[string]any{"queue": queueName}, edges)
				if hasGet {
					edges = jvmhelpers.AddMessagingEdge(classNodeID, queueID, model.EdgeReceivesFrom,
						className+" receives from "+queueName, map[string]any{"queue": queueName}, edges)
				}
			case hasGet:
				edges = jvmhelpers.AddMessagingEdge(classNodeID, queueID, model.EdgeReceivesFrom,
					className+" receives from "+queueName, map[string]any{"queue": queueName}, edges)
			default:
				edges = jvmhelpers.AddMessagingEdge(classNodeID, queueID, model.EdgeConnectsTo,
					className+" accesses "+queueName, map[string]any{"queue": queueName}, edges)
			}
		}
	}

	// JMS createQueue / createTopic
	for _, line := range lines {
		if m := ibmJmsCreateQueueRE.FindStringSubmatch(line); m != nil {
			ensureIbmNode("ibmmq:queue:"+m[1], m[1], model.NodeQueue, "ibmmq:queue:"+m[1],
				map[string]any{"broker": "ibm_mq", "queue": m[1]}, seenQueues, &nodes)
		}
		if m := ibmJmsCreateTopicRE.FindStringSubmatch(line); m != nil {
			ensureIbmNode("ibmmq:topic:"+m[1], m[1], model.NodeTopic, "ibmmq:topic:"+m[1],
				map[string]any{"broker": "ibm_mq", "topic": m[1]}, seenTopics, &nodes)
		}
	}

	if ibmMqTopicDeclRE.MatchString(text) && len(seenTopics) == 0 {
		n := model.NewCodeNode("ibmmq:topic:__unknown__", model.NodeTopic, "ibmmq:topic:unknown")
		n.Source = "IbmMqDetector"
		n.Properties["broker"] = "ibm_mq"
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, edges)
}

func ensureIbmNode(id, name string, kind model.NodeKind, label string, props map[string]any, seen map[string]bool, nodes *[]*model.CodeNode) string {
	if !seen[name] {
		seen[name] = true
		n := model.NewCodeNode(id, kind, label)
		n.Source = "IbmMqDetector"
		for k, v := range props {
			n.Properties[k] = v
		}
		*nodes = append(*nodes, n)
	}
	return id
}
