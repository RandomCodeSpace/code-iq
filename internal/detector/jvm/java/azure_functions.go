package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// AzureFunctionsDetector mirrors Java AzureFunctionsDetector. Detects
// @FunctionName + trigger annotations (HTTP / ServiceBusQueue / ServiceBusTopic /
// EventHub / Timer / CosmosDB).
type AzureFunctionsDetector struct{}

func NewAzureFunctionsDetector() *AzureFunctionsDetector { return &AzureFunctionsDetector{} }

func (AzureFunctionsDetector) Name() string { return "azure_functions" }
func (AzureFunctionsDetector) SupportedLanguages() []string {
	return []string{"java", "csharp", "typescript", "javascript"}
}
func (AzureFunctionsDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewAzureFunctionsDetector()) }

var (
	afFunctionNameRE = regexp.MustCompile(`@FunctionName\s*\(\s*"([^"]+)"`)
	afHttpTrigRE     = regexp.MustCompile(`@HttpTrigger\s*\(`)
	afSbQueueRE      = regexp.MustCompile(`@ServiceBusQueueTrigger\s*\([^)]*queueName\s*=\s*"([^"]+)"`)
	afSbTopicRE      = regexp.MustCompile(`@ServiceBusTopicTrigger\s*\([^)]*topicName\s*=\s*"([^"]+)"`)
	afEhTrigRE       = regexp.MustCompile(`@EventHubTrigger\s*\([^)]*eventHubName\s*=\s*"([^"]+)"`)
	afTimerTrigRE    = regexp.MustCompile(`@TimerTrigger\s*\([^)]*schedule\s*=\s*"([^"]+)"`)
	afCosmosTrigRE   = regexp.MustCompile(`@CosmosDB(?:Trigger|Input|Output)\s*\(`)
	afClassRE        = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
)

func (AzureFunctionsDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "FunctionName") && !strings.Contains(text, "@FunctionName") &&
		!strings.Contains(text, "@HttpTrigger") {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	className := ""
	for _, line := range lines {
		if m := afClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			break
		}
	}

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	for i, line := range lines {
		fn := afFunctionNameRE.FindStringSubmatch(line)
		if fn == nil {
			continue
		}
		funcName := fn[1]
		funcID := "azure:func:" + funcName
		end := i + 15
		if end > len(lines) {
			end = len(lines)
		}
		contextLines := strings.Join(lines[i:end], "\n")
		props := map[string]any{}

		if afHttpTrigRE.FindStringIndex(contextLines) != nil {
			props["trigger_type"] = "http"
			nodes = append(nodes, afFunc(funcID, funcName, className, i+1, ctx,
				[]string{"@FunctionName", "@HttpTrigger"}, props))
			epID := funcID + ":endpoint"
			en := model.NewCodeNode(epID, model.NodeEndpoint, "HTTP "+funcName)
			en.FilePath = ctx.FilePath
			en.LineStart = i + 1
			en.Source = "AzureFunctionsDetector"
			en.Confidence = base.RegexDetectorDefaultConfidence
			en.Properties["http_trigger"] = true
			en.Properties["function_name"] = funcName
			nodes = append(nodes, en)
			edges = append(edges, model.NewCodeEdge(funcID+"->exposes->"+epID, model.EdgeExposes, funcID, epID))
			continue
		}
		if m := afSbQueueRE.FindStringSubmatch(contextLines); m != nil {
			queue := m[1]
			props["trigger_type"] = "serviceBusQueue"
			props["queue_name"] = queue
			nodes = append(nodes, afFunc(funcID, funcName, className, i+1, ctx,
				[]string{"@FunctionName", "@ServiceBusQueueTrigger"}, props))
			qID := "azure:servicebus:queue:" + queue
			qn := model.NewCodeNode(qID, model.NodeQueue, "servicebus:"+queue)
			qn.FilePath = ctx.FilePath
			qn.LineStart = i + 1
			qn.Source = "AzureFunctionsDetector"
			qn.Confidence = base.RegexDetectorDefaultConfidence
			qn.Properties["broker"] = "azure_servicebus"
			qn.Properties["queue"] = queue
			nodes = append(nodes, qn)
			edges = append(edges, model.NewCodeEdge(qID+"->triggers->"+funcID, model.EdgeTriggers, qID, funcID))
			continue
		}
		if m := afSbTopicRE.FindStringSubmatch(contextLines); m != nil {
			topic := m[1]
			props["trigger_type"] = "serviceBusTopic"
			props["topic_name"] = topic
			nodes = append(nodes, afFunc(funcID, funcName, className, i+1, ctx,
				[]string{"@FunctionName", "@ServiceBusTopicTrigger"}, props))
			tID := "azure:servicebus:topic:" + topic
			tn := model.NewCodeNode(tID, model.NodeTopic, "servicebus:"+topic)
			tn.FilePath = ctx.FilePath
			tn.LineStart = i + 1
			tn.Source = "AzureFunctionsDetector"
			tn.Confidence = base.RegexDetectorDefaultConfidence
			tn.Properties["broker"] = "azure_servicebus"
			tn.Properties["topic"] = topic
			nodes = append(nodes, tn)
			edges = append(edges, model.NewCodeEdge(tID+"->triggers->"+funcID, model.EdgeTriggers, tID, funcID))
			continue
		}
		if m := afEhTrigRE.FindStringSubmatch(contextLines); m != nil {
			hub := m[1]
			props["trigger_type"] = "eventHub"
			props["event_hub_name"] = hub
			nodes = append(nodes, afFunc(funcID, funcName, className, i+1, ctx,
				[]string{"@FunctionName", "@EventHubTrigger"}, props))
			hID := "azure:eventhub:" + hub
			hn := model.NewCodeNode(hID, model.NodeTopic, "eventhub:"+hub)
			hn.FilePath = ctx.FilePath
			hn.LineStart = i + 1
			hn.Source = "AzureFunctionsDetector"
			hn.Confidence = base.RegexDetectorDefaultConfidence
			hn.Properties["broker"] = "azure_eventhub"
			hn.Properties["event_hub"] = hub
			nodes = append(nodes, hn)
			edges = append(edges, model.NewCodeEdge(hID+"->triggers->"+funcID, model.EdgeTriggers, hID, funcID))
			continue
		}
		if m := afTimerTrigRE.FindStringSubmatch(contextLines); m != nil {
			props["trigger_type"] = "timer"
			props["schedule"] = m[1]
			nodes = append(nodes, afFunc(funcID, funcName, className, i+1, ctx,
				[]string{"@FunctionName", "@TimerTrigger"}, props))
			continue
		}
		if afCosmosTrigRE.FindStringIndex(contextLines) != nil {
			props["trigger_type"] = "cosmosDB"
			nodes = append(nodes, afFunc(funcID, funcName, className, i+1, ctx,
				[]string{"@FunctionName", "@CosmosDBTrigger"}, props))
			rID := "azure:cosmos:func:" + funcName
			rn := model.NewCodeNode(rID, model.NodeAzureResource, "cosmosdb:"+funcName)
			rn.FilePath = ctx.FilePath
			rn.LineStart = i + 1
			rn.Source = "AzureFunctionsDetector"
			rn.Confidence = base.RegexDetectorDefaultConfidence
			rn.Properties["cosmos_type"] = "trigger"
			rn.Properties["function_name"] = funcName
			nodes = append(nodes, rn)
			edges = append(edges, model.NewCodeEdge(rID+"->triggers->"+funcID, model.EdgeTriggers, rID, funcID))
			continue
		}

		props["trigger_type"] = "unknown"
		nodes = append(nodes, afFunc(funcID, funcName, className, i+1, ctx,
			[]string{"@FunctionName"}, props))
	}

	return detector.ResultOf(nodes, edges)
}

func afFunc(id, funcName, className string, line int, ctx *detector.Context, anns []string, props map[string]any) *model.CodeNode {
	fqn := funcName
	if className != "" {
		fqn = className + "." + funcName
	}
	n := model.NewCodeNode(id, model.NodeAzureFunction, funcName)
	n.FQN = fqn
	n.FilePath = ctx.FilePath
	n.LineStart = line
	n.Source = "AzureFunctionsDetector"
	n.Confidence = base.RegexDetectorDefaultConfidence
	n.Annotations = append(n.Annotations, anns...)
	for k, v := range props {
		n.Properties[k] = v
	}
	return n
}
