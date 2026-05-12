package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// WebSocketDetector mirrors Java WebSocketDetector. Detects JSR-356
// @ServerEndpoint, Spring STOMP @MessageMapping/@SendTo, STOMP endpoint
// registration, and SimpMessagingTemplate sends.
type WebSocketDetector struct{}

func NewWebSocketDetector() *WebSocketDetector { return &WebSocketDetector{} }

func (WebSocketDetector) Name() string { return "websocket" }
func (WebSocketDetector) SupportedLanguages() []string {
	return []string{"java"}
}
func (WebSocketDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewWebSocketDetector()) }

var (
	wsClassRE         = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	wsServerEndRE     = regexp.MustCompile(`@ServerEndpoint\s*\(\s*(?:value\s*=\s*)?"([^"]+)"`)
	wsMessageMapRE    = regexp.MustCompile(`@MessageMapping\s*\(\s*"([^"]+)"`)
	wsSendToRE        = regexp.MustCompile(`@SendTo\s*\(\s*"([^"]+)"`)
	wsSendToUserRE    = regexp.MustCompile(`@SendToUser\s*\(\s*"([^"]+)"`)
	wsStompEndRE      = regexp.MustCompile(`(?s)registerStompEndpoints.*?\.addEndpoint\s*\(\s*"([^"]+)"`)
	wsMsgTemplateRE   = regexp.MustCompile(`(?:simpMessagingTemplate|messagingTemplate)\s*\.(?:convertAndSend|convertAndSendToUser)\s*\(\s*"([^"]+)"`)
	wsMethodRE        = regexp.MustCompile(`(?:public|protected|private)?\s*(?:[\w<>\[\],?\s]+)\s+(\w+)\s*\(`)
)

func (WebSocketDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "@ServerEndpoint") && !strings.Contains(text, "@MessageMapping") &&
		!strings.Contains(text, "WebSocketHandler") && !strings.Contains(text, "registerStompEndpoints") &&
		!strings.Contains(text, "SimpMessagingTemplate") && !strings.Contains(text, "simpMessagingTemplate") &&
		!strings.Contains(text, "messagingTemplate") && !strings.Contains(text, "@SendTo") &&
		!strings.Contains(text, "@SendToUser") {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	className := ""
	for _, line := range lines {
		if m := wsClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			break
		}
	}
	if className == "" {
		return detector.EmptyResult()
	}
	classID := ctx.FilePath + ":" + className

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	for _, m := range wsServerEndRE.FindAllStringSubmatchIndex(text, -1) {
		path := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		id := "ws:endpoint:" + path
		n := model.NewCodeNode(id, model.NodeWebSocketEndpoint, "WS "+path)
		n.FQN = className + ":" + path
		n.FilePath = ctx.FilePath
		n.LineStart = line
		n.Source = "WebSocketDetector"
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Annotations = append(n.Annotations, "@ServerEndpoint")
		n.Properties["path"] = path
		n.Properties["protocol"] = "websocket"
		n.Properties["type"] = "jsr356"
		nodes = append(nodes, n)
		edges = append(edges, model.NewCodeEdge(classID+"->exposes->"+id, model.EdgeExposes, classID, id))
	}

	for i, line := range lines {
		m := wsMessageMapRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		dest := m[1]
		methodName := ""
		end := i + 5
		if end > len(lines) {
			end = len(lines)
		}
		for k := i + 1; k < end; k++ {
			if mm := wsMethodRE.FindStringSubmatch(lines[k]); mm != nil {
				methodName = mm[1]
				break
			}
		}
		id := "ws:message:" + dest
		fqn := className + ".unknown"
		if methodName != "" {
			fqn = className + "." + methodName
		}
		n := model.NewCodeNode(id, model.NodeWebSocketEndpoint, "WS MSG "+dest)
		n.FQN = fqn
		n.FilePath = ctx.FilePath
		n.LineStart = i + 1
		n.Source = "WebSocketDetector"
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Annotations = append(n.Annotations, "@MessageMapping")
		n.Properties["destination"] = dest
		n.Properties["protocol"] = "websocket"
		n.Properties["type"] = "stomp"
		nodes = append(nodes, n)
		edges = append(edges, model.NewCodeEdge(classID+"->exposes->"+id, model.EdgeExposes, classID, id))

		for k := i + 1; k < end; k++ {
			var st []string
			if s := wsSendToRE.FindStringSubmatch(lines[k]); s != nil {
				st = s
			} else if s := wsSendToUserRE.FindStringSubmatch(lines[k]); s != nil {
				st = s
			}
			if st != nil {
				sendDest := st[1]
				sendID := "ws:topic:" + sendDest
				sn := model.NewCodeNode(sendID, model.NodeWebSocketEndpoint, "WS TOPIC "+sendDest)
				sn.FilePath = ctx.FilePath
				sn.LineStart = k + 1
				sn.Source = "WebSocketDetector"
				sn.Confidence = base.RegexDetectorDefaultConfidence
				sn.Properties["destination"] = sendDest
				sn.Properties["protocol"] = "websocket"
				nodes = append(nodes, sn)
				edges = append(edges, model.NewCodeEdge(id+"->produces->"+sendID, model.EdgeProduces, id, sendID))
			}
		}
	}

	for _, m := range wsStompEndRE.FindAllStringSubmatchIndex(text, -1) {
		path := text[m[2]:m[3]]
		id := "ws:stomp:" + path
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(id, model.NodeWebSocketEndpoint, "STOMP "+path)
		n.FilePath = ctx.FilePath
		n.LineStart = line
		n.Source = "WebSocketDetector"
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["path"] = path
		n.Properties["protocol"] = "stomp"
		n.Properties["type"] = "stomp_endpoint"
		nodes = append(nodes, n)
	}

	for _, m := range wsMsgTemplateRE.FindAllStringSubmatch(text, -1) {
		dest := m[1]
		targetID := "ws:topic:" + dest
		e := model.NewCodeEdge(classID+"->produces->"+targetID, model.EdgeProduces, classID, targetID)
		e.Properties["destination"] = dest
		edges = append(edges, e)
	}

	return detector.ResultOf(nodes, edges)
}
