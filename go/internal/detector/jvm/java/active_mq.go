package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/detector/jvm/jvmhelpers"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// ActiveMqDetector mirrors Java ActiveMqDetector. Disambiguates Classic vs
// Artemis via import path.
type ActiveMqDetector struct{}

func NewActiveMqDetector() *ActiveMqDetector { return &ActiveMqDetector{} }

func (ActiveMqDetector) Name() string                 { return "active_mq" }
func (ActiveMqDetector) SupportedLanguages() []string { return []string{"java"} }
func (ActiveMqDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewActiveMqDetector()) }

const (
	amqClassic = "activemq"
	amqArtemis = "activemq_artemis"
)

var (
	amqArtemisImportRE = regexp.MustCompile(`import\s+org\.apache\.activemq\.artemis\.|org\.apache\.activemq\.artemis\.`)
	// Go RE2 lacks negative lookahead. Match `import org.apache.activemq.` and
	// then reject Artemis matches in code after capturing what comes next.
	amqClassicImportRE = regexp.MustCompile(`import\s+org\.apache\.activemq\.(\w+)`)
	amqFactoryRE       = regexp.MustCompile(
		`\b(ActiveMQConnectionFactory|ActiveMQQueueConnectionFactory|ActiveMQTopicConnectionFactory|ActiveMQJMSConnectionFactory|ActiveMQXAConnectionFactory|PooledConnectionFactory)\b`,
	)
	amqBrokerURLRE = regexp.MustCompile(
		`"((?:(?:tcp|ssl|nio|udp|vm|amqp|stomp|mqtt|ws|wss)(?:\+nio|\+ssl)?://[^"]+|failover:[^"]+))"`,
	)
	amqSpringBrokerURLRE = regexp.MustCompile(
		`(?m)^\s*spring\.(activemq|artemis)\.broker[._-]url\s*[=:]\s*(\S+)`,
	)
	amqQueueRE       = regexp.MustCompile(`new\s+ActiveMQQueue\s*\(\s*"([^"]+)"`)
	amqTopicRE       = regexp.MustCompile(`new\s+ActiveMQTopic\s*\(\s*"([^"]+)"`)
	amqCreateQueueRE = regexp.MustCompile(`createQueue\s*\(\s*"([^"]+)"`)
	amqCreateTopicRE = regexp.MustCompile(`createTopic\s*\(\s*"([^"]+)"`)
	amqSendRE        = regexp.MustCompile(`\bsend\s*\(`)
	amqPublishRE     = regexp.MustCompile(`\bpublish\s*\(`)
	amqReceiveRE     = regexp.MustCompile(`\breceive\s*\(`)
	amqOnMessageRE   = regexp.MustCompile(`\bonMessage\s*\(`)
	amqProducerRE    = regexp.MustCompile(`\bMessageProducer\b`)
	amqConsumerRE    = regexp.MustCompile(`\bMessageConsumer\b`)
)

func (d ActiveMqDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}

	hasArtemis := amqArtemisImportRE.MatchString(text)
	hasClassic := false
	if !hasArtemis {
		for _, m := range amqClassicImportRE.FindAllStringSubmatch(text, -1) {
			// Reject the Artemis package — `org.apache.activemq.artemis.*`.
			if m[1] != "artemis" {
				hasClassic = true
				break
			}
		}
	}
	hasClassRef := strings.Contains(text, "ActiveMQConnectionFactory") ||
		strings.Contains(text, "ActiveMQQueue") ||
		strings.Contains(text, "ActiveMQTopic") ||
		strings.Contains(text, "ActiveMQJMSConnectionFactory")
	hasSpringConfig := strings.Contains(text, "spring.activemq.") || strings.Contains(text, "spring.artemis.")

	if !hasArtemis && !hasClassic && !hasClassRef && !hasSpringConfig {
		return detector.EmptyResult()
	}

	broker := amqClassic
	if hasArtemis {
		broker = amqArtemis
	}

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	seenQueues := map[string]bool{}
	seenTopics := map[string]bool{}

	// Spring config — emit broker node without class context.
	for _, m := range amqSpringBrokerURLRE.FindAllStringSubmatch(text, -1) {
		flavor := strings.ToLower(m[1])
		detected := amqClassic
		if flavor == "artemis" {
			detected = amqArtemis
		}
		url := strings.Trim(m[2], `"'`)
		nodeID := "amq:server:" + detected + ":" + url
		n := model.NewCodeNode(nodeID, model.NodeMessageQueue, detected+":"+url)
		n.Source = "ActiveMqDetector"
		n.Properties["broker"] = detected
		n.Properties["broker_url"] = url
		nodes = append(nodes, n)
	}

	className := jvmhelpers.ExtractClassName(text)
	if className == "" {
		return detector.ResultOf(nodes, edges)
	}
	classNodeID := ctx.FilePath + ":" + className
	lines := strings.Split(text, "\n")

	isProducer := amqSendRE.MatchString(text) || amqPublishRE.MatchString(text) || amqProducerRE.MatchString(text)
	isConsumer := amqReceiveRE.MatchString(text) || amqOnMessageRE.MatchString(text) || amqConsumerRE.MatchString(text)

	for i, line := range lines {
		m := amqFactoryRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		factoryType := m[1]
		var url string
		startWin := max0(i - 1)
		endWin := min0(len(lines), i+4)
		for j := startWin; j < endWin; j++ {
			if u := amqBrokerURLRE.FindStringSubmatch(lines[j]); u != nil {
				url = u[1]
				break
			}
		}
		nodeID := "amq:server:" + broker + ":" + factoryType
		if url != "" {
			nodeID += ":" + url
		}
		n := model.NewCodeNode(nodeID, model.NodeMessageQueue, broker+":"+factoryType)
		n.Source = "ActiveMqDetector"
		n.Properties["broker"] = broker
		n.Properties["factory_type"] = factoryType
		if url != "" {
			n.Properties["broker_url"] = url
		}
		nodes = append(nodes, n)
		edges = jvmhelpers.AddMessagingEdge(classNodeID, nodeID, model.EdgeConnectsTo,
			"", map[string]any{"factory_type": factoryType}, edges)
	}

	for _, line := range lines {
		if mq := amqQueueRE.FindStringSubmatch(line); mq != nil {
			name := mq[1]
			qid := ensureAmqQueue(name, broker, seenQueues, &nodes)
			if isProducer {
				edges = jvmhelpers.AddMessagingEdge(classNodeID, qid, model.EdgeSendsTo,
					"", map[string]any{"queue": name}, edges)
			}
			if isConsumer {
				edges = jvmhelpers.AddMessagingEdge(classNodeID, qid, model.EdgeReceivesFrom,
					"", map[string]any{"queue": name}, edges)
			}
		}
		if mt := amqTopicRE.FindStringSubmatch(line); mt != nil {
			name := mt[1]
			tid := ensureAmqTopic(name, broker, seenTopics, &nodes)
			if isProducer {
				edges = jvmhelpers.AddMessagingEdge(classNodeID, tid, model.EdgeSendsTo,
					"", map[string]any{"topic": name}, edges)
			}
			if isConsumer {
				edges = jvmhelpers.AddMessagingEdge(classNodeID, tid, model.EdgeReceivesFrom,
					"", map[string]any{"topic": name}, edges)
			}
		}
	}

	isAmqContext := hasArtemis || hasClassic || hasClassRef
	if isAmqContext {
		for _, line := range lines {
			if cq := amqCreateQueueRE.FindStringSubmatch(line); cq != nil {
				name := cq[1]
				qid := ensureAmqQueue(name, broker, seenQueues, &nodes)
				if isProducer {
					edges = jvmhelpers.AddMessagingEdge(classNodeID, qid, model.EdgeSendsTo,
						"", map[string]any{"queue": name}, edges)
				}
				if isConsumer {
					edges = jvmhelpers.AddMessagingEdge(classNodeID, qid, model.EdgeReceivesFrom,
						"", map[string]any{"queue": name}, edges)
				}
			}
			if ct := amqCreateTopicRE.FindStringSubmatch(line); ct != nil {
				name := ct[1]
				tid := ensureAmqTopic(name, broker, seenTopics, &nodes)
				if isProducer {
					edges = jvmhelpers.AddMessagingEdge(classNodeID, tid, model.EdgeSendsTo,
						"", map[string]any{"topic": name}, edges)
				}
				if isConsumer {
					edges = jvmhelpers.AddMessagingEdge(classNodeID, tid, model.EdgeReceivesFrom,
						"", map[string]any{"topic": name}, edges)
				}
			}
		}
	}

	return detector.ResultOf(nodes, edges)
}

func ensureAmqQueue(name, broker string, seen map[string]bool, nodes *[]*model.CodeNode) string {
	id := "amq:queue:" + broker + ":" + name
	if !seen[name] {
		seen[name] = true
		n := model.NewCodeNode(id, model.NodeQueue, broker+":queue:"+name)
		n.Source = "ActiveMqDetector"
		n.Properties["broker"] = broker
		n.Properties["queue"] = name
		*nodes = append(*nodes, n)
	}
	return id
}

func ensureAmqTopic(name, broker string, seen map[string]bool, nodes *[]*model.CodeNode) string {
	id := "amq:topic:" + broker + ":" + name
	if !seen[name] {
		seen[name] = true
		n := model.NewCodeNode(id, model.NodeTopic, broker+":topic:"+name)
		n.Source = "ActiveMqDetector"
		n.Properties["broker"] = broker
		n.Properties["topic"] = name
		*nodes = append(*nodes, n)
	}
	return id
}
