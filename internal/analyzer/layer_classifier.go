package analyzer

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/model"
)

// LayerClassifier assigns a Layer value to every CodeNode based on
// (kind, framework, file_path) heuristics. Pure, deterministic, first-match
// wins. Priority order mirrors LayerClassifier.java:
//   1. Node kind (frontend / backend / infra)
//   2. Language (infra)
//   3. File extension + path
//   4. Framework
//   5. Shared node kinds
//   6. Fallback package/path heuristics + Java src/main convention
type LayerClassifier struct{}

var (
	frontendKinds = map[model.NodeKind]struct{}{
		model.NodeComponent: {},
		model.NodeHook:      {},
	}
	backendKinds = map[model.NodeKind]struct{}{
		model.NodeGuard:              {},
		model.NodeMiddleware:         {},
		model.NodeEndpoint:           {},
		model.NodeRepository:         {},
		model.NodeDatabaseConnection: {},
		model.NodeQuery:              {},
		model.NodeEntity:             {},
		model.NodeMigration:          {},
		model.NodeService:            {},
		model.NodeTopic:              {},
		model.NodeQueue:              {},
		model.NodeEvent:              {},
		model.NodeMessageQueue:       {},
		model.NodeRMIInterface:       {},
		model.NodeWebSocketEndpoint:  {},
	}
	infraKinds = map[model.NodeKind]struct{}{
		model.NodeInfraResource: {},
		model.NodeAzureResource: {},
		model.NodeAzureFunction: {},
		model.NodeSQLEntity:     {},
	}
	sharedKinds = map[model.NodeKind]struct{}{
		model.NodeConfigFile:       {},
		model.NodeConfigKey:        {},
		model.NodeConfigDefinition: {},
		model.NodeProtocolMessage:  {},
	}
	infraLangs = map[string]struct{}{
		"terraform":  {},
		"bicep":      {},
		"dockerfile": {},
	}
	frontendFrameworks = map[string]struct{}{
		"react":   {},
		"vue":     {},
		"angular": {},
		"svelte":  {},
		"nextjs":  {},
	}
	backendFrameworks = map[string]struct{}{
		"express":         {},
		"nestjs":          {},
		"flask":           {},
		"django":          {},
		"fastapi":         {},
		"spring":          {},
		"spring_boot":     {},
		"spring_mvc":      {},
		"spring_data":     {},
		"spring_security": {},
		"gin":             {},
		"echo":            {},
		"fiber":           {},
		"actix":           {},
		"rocket":          {},
		"axum":            {},
		"asp.net":         {},
		"koa":             {},
		"hapi":            {},
		"fastify":         {},
	}

	frontendPathRE = regexp.MustCompile(`(?:^|/)(?:src/)?(?:components|pages|views|app/ui|public)/`)
	backendPathRE  = regexp.MustCompile(`(?:^|/)(?:src/)?(?:server|api|controllers|services|routes|handlers)/`)
	frontendExtRE  = regexp.MustCompile(`\.(?:tsx|jsx)$`)
	backendPkgRE   = regexp.MustCompile(`(?i)(?:^|/|\.)(?:controller|controllers|api|web|rest|resource|resources|model|models|entity|entities|domain|dto|dtos|repository|repositories|dao|persistence|service|services|business|logic|routes|handlers|handler|middleware|middlewares|schemas)(?:/|\.|$)`)
	sharedPkgRE    = regexp.MustCompile(`(?i)(?:^|/|\.)(?:config|configuration|util|utils|helper|helpers|common|shared|exception|exceptions|constants|enums)(?:/|\.|$)`)
	frontendPkgRE  = regexp.MustCompile(`(?i)(?:^|/|\.)(?:components|views|pages|ui|widgets|screens|templates|layouts)(?:/|\.|$)`)
)

// Classify sets the Layer property on every node in the slice.
func (c *LayerClassifier) Classify(nodes []*model.CodeNode) {
	for _, n := range nodes {
		n.Layer = c.classifyOne(n)
	}
}

// classifyOne returns the Layer for a single node. Exported as lowercase
// because callers should go through Classify; exposed package-internally so
// tests can exercise individual rules without a slice.
func (c *LayerClassifier) classifyOne(n *model.CodeNode) model.Layer {
	// 1. Node kind rules.
	if _, ok := frontendKinds[n.Kind]; ok {
		return model.LayerFrontend
	}
	if _, ok := backendKinds[n.Kind]; ok {
		return model.LayerBackend
	}
	if _, ok := infraKinds[n.Kind]; ok {
		return model.LayerInfra
	}

	// 2. Language rules.
	if lang, _ := n.Properties["language"].(string); lang != "" {
		if _, ok := infraLangs[lang]; ok {
			return model.LayerInfra
		}
	}

	// 3. File path rules.
	if n.FilePath != "" {
		if frontendExtRE.MatchString(n.FilePath) {
			return model.LayerFrontend
		}
		if frontendPathRE.MatchString(n.FilePath) {
			return model.LayerFrontend
		}
		if backendPathRE.MatchString(n.FilePath) {
			return model.LayerBackend
		}
	}

	// 4. Framework rules.
	if fw, _ := n.Properties["framework"].(string); fw != "" {
		if _, ok := frontendFrameworks[fw]; ok {
			return model.LayerFrontend
		}
		if _, ok := backendFrameworks[fw]; ok {
			return model.LayerBackend
		}
	}

	// 5. Shared node kinds.
	if _, ok := sharedKinds[n.Kind]; ok {
		return model.LayerShared
	}

	// 6. Fallback: package-name / path-pattern heuristics over both file path
	// and node ID (the ID often carries package info for JVM-style IDs).
	combined := n.FilePath + "|" + n.ID
	if frontendPkgRE.MatchString(combined) {
		return model.LayerFrontend
	}
	if backendPkgRE.MatchString(combined) {
		return model.LayerBackend
	}
	if sharedPkgRE.MatchString(combined) {
		return model.LayerShared
	}

	// 7. Java-family final fallback: files under src/main/java or
	// src/main/kotlin in standard Spring/Java layouts are virtually always
	// backend code.
	if strings.HasSuffix(n.FilePath, ".java") ||
		strings.HasSuffix(n.FilePath, ".kt") ||
		strings.HasSuffix(n.FilePath, ".scala") {
		if strings.Contains(n.FilePath, "src/main/java/") ||
			strings.Contains(n.FilePath, "src/main/kotlin/") {
			return model.LayerBackend
		}
	}

	return model.LayerUnknown
}
