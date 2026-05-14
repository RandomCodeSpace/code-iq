package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// ConfigDefDetector mirrors Java ConfigDefDetector regex tier.
// Detects:
//   - Kafka ConfigDef.define("key", ...)
//   - Spring @Value("${app.key}")
//   - Spring @ConfigurationProperties("prefix")
type ConfigDefDetector struct{}

func NewConfigDefDetector() *ConfigDefDetector { return &ConfigDefDetector{} }

func (ConfigDefDetector) Name() string                 { return "config_def" }
func (ConfigDefDetector) SupportedLanguages() []string { return []string{"java"} }
func (ConfigDefDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewConfigDefDetector()) }

var (
	cdClassRE       = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	cdDefineRE      = regexp.MustCompile(`\.define\s*\(\s*"([^"]+)"`)
	cdValueRE       = regexp.MustCompile(`@Value\s*\(\s*"\$\{([^}]+)\}"`)
	cdConfigPropsRE = regexp.MustCompile(`@ConfigurationProperties\s*\(\s*(?:prefix\s*=\s*)?"([^"]+)"`)
)

func (d ConfigDefDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	hasConfigDef := strings.Contains(text, "ConfigDef")
	hasValue := strings.Contains(text, "@Value")
	hasConfigProps := strings.Contains(text, "@ConfigurationProperties")
	if !hasConfigDef && !hasValue && !hasConfigProps {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	var className string
	for _, line := range lines {
		if m := cdClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			break
		}
	}
	if className == "" {
		return detector.EmptyResult()
	}

	classNodeID := ctx.FilePath + ":" + className
	seenKeys := map[string]bool{}

	for i, line := range lines {
		// Kafka ConfigDef.define("...")
		if m := cdDefineRE.FindStringSubmatch(line); m != nil {
			configKey := m[1]
			if !seenKeys[configKey] {
				seenKeys[configKey] = true
				addConfigDefNode(configKey, "kafka_configdef", classNodeID, ctx.FilePath, i+1, &nodes, &edges)
			}
		}
		// Spring @Value("${...}") — can appear multiple times per line
		for _, vm := range cdValueRE.FindAllStringSubmatch(line, -1) {
			key := vm[1]
			if !seenKeys[key] {
				seenKeys[key] = true
				addConfigDefNode(key, "spring_value", classNodeID, ctx.FilePath, i+1, &nodes, &edges)
			}
		}
		// Spring @ConfigurationProperties("prefix")
		if cpm := cdConfigPropsRE.FindStringSubmatch(line); cpm != nil {
			prefix := cpm[1]
			if !seenKeys[prefix] {
				seenKeys[prefix] = true
				addConfigDefNode(prefix, "spring_config_props", classNodeID, ctx.FilePath, i+1, &nodes, &edges)
			}
		}
	}

	return detector.ResultOf(nodes, edges)
}

func addConfigDefNode(key, source, classNodeID, filePath string, line int, nodes *[]*model.CodeNode, edges *[]*model.CodeEdge) {
	nodeID := "config:" + key
	n := model.NewCodeNode(nodeID, model.NodeConfigDefinition, key)
	n.FilePath = filePath
	n.LineStart = line
	n.Source = "ConfigDefDetector"
	n.Properties["config_key"] = key
	n.Properties["config_source"] = source
	*nodes = append(*nodes, n)

	e := model.NewCodeEdge(classNodeID+"->reads_config->"+nodeID, model.EdgeReadsConfig, classNodeID, nodeID)
	e.Properties["config_key"] = key
	*edges = append(*edges, e)
}
