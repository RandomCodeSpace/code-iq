package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// RmiDetector mirrors Java RmiDetector. Detects RMI remote interfaces,
// UnicastRemoteObject implementations, and Registry/Naming bind/lookup
// invocations.
type RmiDetector struct{}

func NewRmiDetector() *RmiDetector { return &RmiDetector{} }

func (RmiDetector) Name() string                        { return "rmi" }
func (RmiDetector) SupportedLanguages() []string        { return []string{"java"} }
func (RmiDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewRmiDetector()) }

var (
	rmiRemoteIfaceRE   = regexp.MustCompile(`interface\s+(\w+)\s+extends\s+(?:java\.rmi\.)?Remote`)
	rmiUnicastRE       = regexp.MustCompile(`class\s+(\w+)\s+extends\s+(?:java\.rmi\.server\.)?UnicastRemoteObject`)
	rmiImplementsRE    = regexp.MustCompile(`class\s+(\w+)\s+extends\s+\w+\s+implements\s+([\w,\s]+)`)
	rmiRegistryBindRE  = regexp.MustCompile(`(?:Registry|Naming)\s*\.(?:bind|rebind)\s*\(\s*"([^"]+)"`)
	rmiRegistryLookRE  = regexp.MustCompile(`(?:Registry|Naming)\s*\.lookup\s*\(\s*"([^"]+)"`)
	rmiClassFindRE     = regexp.MustCompile(`class\s+(\w+)`)
)

func (RmiDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	hasRemote := strings.Contains(text, "Remote")
	hasUnicast := strings.Contains(text, "UnicastRemoteObject")
	hasNaming := strings.Contains(text, "Naming.") || strings.Contains(text, "Registry.")
	if !hasRemote && !hasUnicast && !hasNaming {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	for i, line := range lines {
		if m := rmiRemoteIfaceRE.FindStringSubmatch(line); m != nil {
			iface := m[1]
			id := ctx.FilePath + ":" + iface
			n := model.NewCodeNode(id, model.NodeRMIInterface, iface)
			n.FQN = iface
			n.FilePath = ctx.FilePath
			n.LineStart = i + 1
			n.Source = "RmiDetector"
			n.Confidence = base.RegexDetectorDefaultConfidence
			n.Properties["type"] = "remote_interface"
			nodes = append(nodes, n)
		}
	}

	for _, line := range lines {
		if m := rmiUnicastRE.FindStringSubmatch(line); m != nil {
			cn := m[1]
			classID := ctx.FilePath + ":" + cn
			if im := rmiImplementsRE.FindStringSubmatch(line); im != nil {
				for _, iface := range strings.Split(im[2], ",") {
					ifaceName := strings.TrimSpace(iface)
					if ifaceName == "" {
						continue
					}
					targetID := "*:" + ifaceName
					edges = append(edges, model.NewCodeEdge(
						classID+"->exports_rmi->"+targetID,
						model.EdgeExportsRMI, classID, targetID))
				}
			}
		}
	}

	for i, line := range lines {
		for _, m := range rmiRegistryBindRE.FindAllStringSubmatch(line, -1) {
			binding := m[1]
			cn := rmiFindEnclosingClass(lines, i)
			if cn == "" {
				continue
			}
			classID := ctx.FilePath + ":" + cn
			targetID := "rmi:binding:" + binding
			e := model.NewCodeEdge(classID+"->exports_rmi->"+targetID, model.EdgeExportsRMI, classID, targetID)
			e.Properties["binding_name"] = binding
			edges = append(edges, e)
		}
		for _, m := range rmiRegistryLookRE.FindAllStringSubmatch(line, -1) {
			binding := m[1]
			cn := rmiFindEnclosingClass(lines, i)
			if cn == "" {
				continue
			}
			classID := ctx.FilePath + ":" + cn
			targetID := "rmi:binding:" + binding
			e := model.NewCodeEdge(classID+"->invokes_rmi->"+targetID, model.EdgeInvokesRMI, classID, targetID)
			e.Properties["binding_name"] = binding
			edges = append(edges, e)
		}
	}

	return detector.ResultOf(nodes, edges)
}

func rmiFindEnclosingClass(lines []string, idx int) string {
	for i := idx; i >= 0; i-- {
		if m := rmiClassFindRE.FindStringSubmatch(lines[i]); m != nil {
			return m[1]
		}
	}
	return ""
}
