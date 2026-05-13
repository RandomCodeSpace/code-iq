package iac

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// DockerfileDetector detects Dockerfile instructions (FROM, EXPOSE, ENV,
// LABEL, ARG, COPY --from). Mirrors Java DockerfileDetector.
type DockerfileDetector struct{}

func NewDockerfileDetector() *DockerfileDetector { return &DockerfileDetector{} }

func (DockerfileDetector) Name() string                        { return "dockerfile" }
func (DockerfileDetector) SupportedLanguages() []string        { return []string{"dockerfile"} }
func (DockerfileDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewDockerfileDetector()) }

var (
	dockerFromRE      = regexp.MustCompile(`(?im)^FROM\s+(\S+)(?:\s+AS\s+(\w+))?`)
	dockerExposeRE    = regexp.MustCompile(`(?m)^EXPOSE\s+(\d+)`)
	dockerEnvRE       = regexp.MustCompile(`(?m)^ENV\s+(\w+)[=\s]`)
	dockerLabelRE     = regexp.MustCompile(`(?m)^LABEL\s+(\S+)=`)
	dockerCopyFromRE  = regexp.MustCompile(`(?im)COPY\s+--from=(\w+)`)
	dockerArgRE       = regexp.MustCompile(`(?m)^ARG\s+(\w+)`)
)

type fromOffset struct {
	offset    int
	nodeIndex int
}

func (d DockerfileDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath
	seen := map[string]bool{}

	// Stage tracking — alias → node id, plus offsets so we can resolve which
	// FROM is the *current* stage at any byte offset later in the file.
	stageNodeIDs := map[string]string{}
	var fromOffsets []fromOffset
	stageOrder := 0

	// FROM
	for _, m := range dockerFromRE.FindAllStringSubmatchIndex(text, -1) {
		image := text[m[2]:m[3]]
		var alias string
		if m[4] >= 0 {
			alias = text[m[4]:m[5]]
		}
		line := base.FindLineNumber(text, m[0])
		nodeID := "docker:" + fp + ":from:" + image
		label := "FROM " + image
		if alias != "" {
			label += " AS " + alias
		}
		n := model.NewCodeNode(nodeID, model.NodeInfraResource, label)
		n.FQN = image
		n.FilePath = fp
		n.LineStart = line
		n.Source = "DockerfileDetector"
		n.Properties["image"] = image
		n.Properties["stage_order"] = stageOrder
		stageOrder++
		if strings.Contains(image, ":") && !strings.HasPrefix(image, "$") {
			parts := strings.SplitN(image, ":", 2)
			n.Properties["image_name"] = parts[0]
			n.Properties["tag"] = parts[1]
		} else {
			n.Properties["image_name"] = image
		}
		if alias != "" {
			n.Properties["stage_alias"] = alias
			n.Properties["build_stage"] = alias
			stageNodeIDs[alias] = nodeID
		}
		fromOffsets = append(fromOffsets, fromOffset{offset: m[0], nodeIndex: len(nodes)})
		nodes = append(nodes, n)

		// Emit anchor nodes so the depends_on edge survives GraphBuilder's
		// phantom-drop filter. Without anchors, fp and image are free-form
		// strings that don't match any CodeNode.
		srcID := base.EnsureFileAnchor(ctx, "dockerfile", "DockerfileDetector", model.ConfidenceLexical, &nodes, seen)
		tgtID := base.EnsureExternalAnchor(image, "docker:image", "DockerfileDetector", model.ConfidenceLexical, &nodes, seen)
		e := model.NewCodeEdge(srcID+":depends_on:"+tgtID, model.EdgeDependsOn, srcID, tgtID)
		e.Source = "DockerfileDetector"
		edges = append(edges, e)
	}

	// COPY --from=alias → DEPENDS_ON between FROMs
	for _, m := range dockerCopyFromRE.FindAllStringSubmatchIndex(text, -1) {
		sourceStage := text[m[2]:m[3]]
		stageID, ok := stageNodeIDs[sourceStage]
		if !ok {
			continue
		}
		// Walk fromOffsets backwards to find the FROM that this COPY belongs to.
		currentNodeID := ""
		for i := len(fromOffsets) - 1; i >= 0; i-- {
			if fromOffsets[i].offset < m[0] {
				currentNodeID = nodes[fromOffsets[i].nodeIndex].ID
				break
			}
		}
		if currentNodeID == "" || currentNodeID == stageID {
			continue
		}
		e := model.NewCodeEdge(
			currentNodeID+":depends_on:"+stageID,
			model.EdgeDependsOn, currentNodeID, stageID,
		)
		e.Source = "DockerfileDetector"
		edges = append(edges, e)
	}

	// EXPOSE
	for _, m := range dockerExposeRE.FindAllStringSubmatchIndex(text, -1) {
		port := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode("docker:"+fp+":expose:"+port, model.NodeEndpoint, "EXPOSE "+port)
		n.FilePath = fp
		n.LineStart = line
		n.Source = "DockerfileDetector"
		n.Properties["port"] = port
		n.Properties["protocol"] = "tcp"
		nodes = append(nodes, n)
	}

	// ENV
	for _, m := range dockerEnvRE.FindAllStringSubmatchIndex(text, -1) {
		key := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode("docker:"+fp+":env:"+key, model.NodeConfigDefinition, "ENV "+key)
		n.FilePath = fp
		n.LineStart = line
		n.Source = "DockerfileDetector"
		n.Properties["env_key"] = key
		nodes = append(nodes, n)
	}

	// LABEL
	for _, m := range dockerLabelRE.FindAllStringSubmatchIndex(text, -1) {
		key := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode("docker:"+fp+":label:"+key, model.NodeConfigDefinition, "LABEL "+key)
		n.FilePath = fp
		n.LineStart = line
		n.Source = "DockerfileDetector"
		n.Properties["label_key"] = key
		nodes = append(nodes, n)
	}

	// ARG
	for _, m := range dockerArgRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode("docker:"+fp+":arg:"+name, model.NodeConfigDefinition, "ARG "+name)
		n.FilePath = fp
		n.LineStart = line
		n.Source = "DockerfileDetector"
		n.Properties["arg_name"] = name
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, edges)
}
