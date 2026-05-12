package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// SpringEventsDetector mirrors Java SpringEventsDetector regex tier.
type SpringEventsDetector struct{}

func NewSpringEventsDetector() *SpringEventsDetector { return &SpringEventsDetector{} }

func (SpringEventsDetector) Name() string                 { return "spring_events" }
func (SpringEventsDetector) SupportedLanguages() []string { return []string{"java"} }
func (SpringEventsDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewSpringEventsDetector()) }

var (
	springEventsClassRE = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	springEventListenRE = regexp.MustCompile(`@EventListener`)
	springTxEventRE     = regexp.MustCompile(`@TransactionalEventListener`)
	springPublishRE     = regexp.MustCompile(
		`(?:applicationEventPublisher|eventPublisher|publisher)\s*\.\s*publishEvent\s*\(\s*(?:new\s+(\w+)|(\w+))`,
	)
	springMethodParamRE = regexp.MustCompile(`(?:public|protected|private)?\s*\w+\s+(\w+)\s*\(\s*(\w+)\s+\w+\)`)
	springEventClassRE  = regexp.MustCompile(`class\s+(\w+)\s+extends\s+\w*Event`)
)

func (d SpringEventsDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	hasListener := strings.Contains(text, "@EventListener") || strings.Contains(text, "@TransactionalEventListener")
	hasPublisher := strings.Contains(text, "publishEvent")
	eventClassMatch := springEventClassRE.FindStringSubmatch(text)
	hasEventClass := eventClassMatch != nil
	if !hasListener && !hasPublisher && !hasEventClass {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	var className string
	for _, line := range lines {
		if m := springEventsClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			break
		}
	}
	if className == "" {
		return detector.EmptyResult()
	}

	classNodeID := ctx.FilePath + ":" + className
	seenEvents := map[string]bool{}

	if hasEventClass {
		ensureEventNode(eventClassMatch[1], seenEvents, &nodes)
	}

	for i, line := range lines {
		if !springEventListenRE.MatchString(line) && !springTxEventRE.MatchString(line) {
			continue
		}
		var eventType string
		for k := i + 1; k < min0(i+5, len(lines)); k++ {
			if pm := springMethodParamRE.FindStringSubmatch(lines[k]); pm != nil {
				eventType = pm[2]
				break
			}
		}
		if eventType != "" {
			eventID := ensureEventNode(eventType, seenEvents, &nodes)
			edges = append(edges, model.NewCodeEdge(classNodeID+"->listens->"+eventID, model.EdgeListens, classNodeID, eventID))
		}
	}

	for _, line := range lines {
		m := springPublishRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		eventType := m[1]
		if eventType == "" {
			eventType = m[2]
		}
		if eventType == "" {
			continue
		}
		eventID := ensureEventNode(eventType, seenEvents, &nodes)
		edges = append(edges, model.NewCodeEdge(classNodeID+"->publishes->"+eventID, model.EdgePublishes, classNodeID, eventID))
	}

	return detector.ResultOf(nodes, edges)
}

func ensureEventNode(eventType string, seen map[string]bool, nodes *[]*model.CodeNode) string {
	eventID := "event:" + eventType
	if !seen[eventType] {
		seen[eventType] = true
		n := model.NewCodeNode(eventID, model.NodeEvent, eventType)
		n.Source = "SpringEventsDetector"
		n.Properties["framework"] = "spring_boot"
		n.Properties["event_class"] = eventType
		*nodes = append(*nodes, n)
	}
	return eventID
}
