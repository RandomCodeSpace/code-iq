package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/detector/jvm/jvmhelpers"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// AzureMessagingDetector mirrors Java AzureMessagingDetector. Java side
// supports Java + TypeScript + JavaScript; Go port limits to Java for now
// (TypeScript/JS detectors live in their own packages owned by another worker).
type AzureMessagingDetector struct{}

func NewAzureMessagingDetector() *AzureMessagingDetector { return &AzureMessagingDetector{} }

func (AzureMessagingDetector) Name() string                 { return "azure_messaging" }
func (AzureMessagingDetector) SupportedLanguages() []string { return []string{"java"} }
func (AzureMessagingDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewAzureMessagingDetector()) }

const (
	azureEventHub   = "azure_eventhub"
	azureServiceBus = "azure_servicebus"
)

var (
	amClassRE             = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	amSbSenderClientRE    = regexp.MustCompile(`\bServiceBusSenderClient\b`)
	amSbReceiverClientRE  = regexp.MustCompile(`\bServiceBusReceiverClient\b`)
	amSbProcessorClientRE = regexp.MustCompile(`\bServiceBusProcessorClient\b`)
	amSbClientRE          = regexp.MustCompile(`\bServiceBusClient\b`)
	amSbClientBuilderRE   = regexp.MustCompile(`\bServiceBusClientBuilder\b`)
	amEhProducerRE        = regexp.MustCompile(`\bEventHubProducerClient\b`)
	amEhConsumerRE        = regexp.MustCompile(`\bEventHubConsumerClient\b`)
	amEhProcessorRE       = regexp.MustCompile(`\bEventProcessorClient\b`)
	amQueueNameRE         = regexp.MustCompile(`(?:queueName|queue)\s*\(\s*"([^"]+)"`)
	amTopicNameRE         = regexp.MustCompile(`(?:topicName|topic)\s*\(\s*"([^"]+)"`)
	amEhNameRE            = regexp.MustCompile(`(?:eventHubName|eventHub)\s*\(\s*"([^"]+)"`)
)

func (d AzureMessagingDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "ServiceBus") && !strings.Contains(text, "EventHub") &&
		!strings.Contains(text, "azure-messaging") {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	var className string
	for _, line := range lines {
		if m := amClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			break
		}
	}
	if className == "" {
		return detector.EmptyResult()
	}
	classNodeID := ctx.FilePath + ":" + className

	isSbSender := amSbSenderClientRE.MatchString(text)
	isSbReceiver := amSbReceiverClientRE.MatchString(text) || amSbProcessorClientRE.MatchString(text)
	isEhProducer := amEhProducerRE.MatchString(text)
	isEhConsumer := amEhConsumerRE.MatchString(text) || amEhProcessorRE.MatchString(text)
	hasSbClient := amSbClientRE.MatchString(text) || amSbClientBuilderRE.MatchString(text)

	var queueNames, topicNames, ehNames []string
	for _, line := range lines {
		if m := amQueueNameRE.FindStringSubmatch(line); m != nil {
			queueNames = append(queueNames, m[1])
		}
		if m := amTopicNameRE.FindStringSubmatch(line); m != nil {
			topicNames = append(topicNames, m[1])
		}
		if m := amEhNameRE.FindStringSubmatch(line); m != nil {
			ehNames = append(ehNames, m[1])
		}
	}

	seenSbQ := map[string]bool{}
	seenSbT := map[string]bool{}
	seenEh := map[string]bool{}

	for _, q := range queueNames {
		qID := ensureAzureSbQueue(q, seenSbQ, &nodes)
		if isSbSender {
			edges = jvmhelpers.AddMessagingEdge(classNodeID, qID, model.EdgeSendsTo,
				"", map[string]any{"queue": q}, edges)
		}
		if isSbReceiver {
			edges = jvmhelpers.AddMessagingEdge(classNodeID, qID, model.EdgeReceivesFrom,
				"", map[string]any{"queue": q}, edges)
		}
	}
	for _, tn := range topicNames {
		tID := ensureAzureSbTopic(tn, seenSbT, &nodes)
		if isSbSender {
			edges = jvmhelpers.AddMessagingEdge(classNodeID, tID, model.EdgeSendsTo,
				"", map[string]any{"topic": tn}, edges)
		}
		if isSbReceiver {
			edges = jvmhelpers.AddMessagingEdge(classNodeID, tID, model.EdgeReceivesFrom,
				"", map[string]any{"topic": tn}, edges)
		}
	}
	for _, e := range ehNames {
		eID := ensureAzureEventHub(e, seenEh, &nodes)
		if isEhProducer {
			edges = jvmhelpers.AddMessagingEdge(classNodeID, eID, model.EdgeSendsTo,
				"", map[string]any{"event_hub": e}, edges)
		}
		if isEhConsumer {
			edges = jvmhelpers.AddMessagingEdge(classNodeID, eID, model.EdgeReceivesFrom,
				"", map[string]any{"event_hub": e}, edges)
		}
	}

	// Fallbacks for generic patterns where no explicit name is given.
	if isSbSender && len(queueNames) == 0 && len(topicNames) == 0 {
		nodes = append(nodes, genericAzureNode("azure:servicebus:__sender__", model.NodeQueue, "azure:servicebus:sender",
			map[string]any{"broker": azureServiceBus, "role": "sender"}))
		edges = jvmhelpers.AddMessagingEdge(classNodeID, "azure:servicebus:__sender__", model.EdgeSendsTo,
			"", map[string]any{}, edges)
	} else if isSbReceiver && len(queueNames) == 0 && len(topicNames) == 0 {
		nodes = append(nodes, genericAzureNode("azure:servicebus:__receiver__", model.NodeQueue, "azure:servicebus:receiver",
			map[string]any{"broker": azureServiceBus, "role": "receiver"}))
		edges = jvmhelpers.AddMessagingEdge(classNodeID, "azure:servicebus:__receiver__", model.EdgeReceivesFrom,
			"", map[string]any{}, edges)
	} else if hasSbClient && len(queueNames) == 0 && len(topicNames) == 0 && !isSbSender && !isSbReceiver {
		nodes = append(nodes, genericAzureNode("azure:servicebus:__client__", model.NodeQueue, "azure:servicebus:client",
			map[string]any{"broker": azureServiceBus, "role": "client"}))
		edges = jvmhelpers.AddMessagingEdge(classNodeID, "azure:servicebus:__client__", model.EdgeConnectsTo,
			"", map[string]any{}, edges)
	}

	if isEhProducer && len(ehNames) == 0 {
		nodes = append(nodes, genericAzureNode("azure:eventhub:__producer__", model.NodeTopic, "azure:eventhub:producer",
			map[string]any{"broker": azureEventHub, "role": "producer"}))
		edges = jvmhelpers.AddMessagingEdge(classNodeID, "azure:eventhub:__producer__", model.EdgeSendsTo,
			"", map[string]any{}, edges)
	} else if isEhConsumer && len(ehNames) == 0 {
		nodes = append(nodes, genericAzureNode("azure:eventhub:__consumer__", model.NodeTopic, "azure:eventhub:consumer",
			map[string]any{"broker": azureEventHub, "role": "consumer"}))
		edges = jvmhelpers.AddMessagingEdge(classNodeID, "azure:eventhub:__consumer__", model.EdgeReceivesFrom,
			"", map[string]any{}, edges)
	}

	return detector.ResultOf(nodes, edges)
}

func ensureAzureSbQueue(name string, seen map[string]bool, nodes *[]*model.CodeNode) string {
	id := "azure:servicebus:" + name
	if !seen[name] {
		seen[name] = true
		*nodes = append(*nodes, genericAzureNode(id, model.NodeQueue, "azure:servicebus:"+name,
			map[string]any{"broker": azureServiceBus, "queue": name}))
	}
	return id
}

func ensureAzureSbTopic(name string, seen map[string]bool, nodes *[]*model.CodeNode) string {
	id := "azure:servicebus:" + name
	if !seen[name] {
		seen[name] = true
		*nodes = append(*nodes, genericAzureNode(id, model.NodeTopic, "azure:servicebus:"+name,
			map[string]any{"broker": azureServiceBus, "topic": name}))
	}
	return id
}

func ensureAzureEventHub(name string, seen map[string]bool, nodes *[]*model.CodeNode) string {
	id := "azure:eventhub:" + name
	if !seen[name] {
		seen[name] = true
		*nodes = append(*nodes, genericAzureNode(id, model.NodeTopic, "azure:eventhub:"+name,
			map[string]any{"broker": azureEventHub, "event_hub": name}))
	}
	return id
}

func genericAzureNode(id string, kind model.NodeKind, label string, props map[string]any) *model.CodeNode {
	n := model.NewCodeNode(id, kind, label)
	n.Source = "AzureMessagingDetector"
	for k, v := range props {
		n.Properties[k] = v
	}
	return n
}
