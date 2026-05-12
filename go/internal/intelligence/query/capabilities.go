package query

import "strings"

// Per-language capability tables mirror
// src/main/java/.../intelligence/query/CapabilityMatrix.java. Levels
// reflect what the current detector suite actually provides:
//
//   - Java                   : 27 detectors + JavaParser AST → EXACT for most.
//   - TypeScript / JS / Python / Go / C# / Rust / C++ : ANTLR → PARTIAL.
//   - Kotlin / Scala / Ruby / PHP / Shell / Markdown / proto / hcl /
//     terraform / dockerfile / yaml / json / toml / ini / properties /
//     xml / sql                                       : regex   → LEXICAL_ONLY.
//   - Everything else        : UNSUPPORTED.

var javaCaps = CapabilityMatrix{
	DimSymbolDefinitions:  LevelExact,
	DimSymbolReferences:   LevelExact,
	DimImportResolution:   LevelExact,
	DimTypeInfo:           LevelExact,
	DimClassHierarchy:     LevelExact,
	DimFrameworkSemantics: LevelExact,
	DimOrmEntityMapping:   LevelExact,
	DimAuthSecurity:       LevelExact,
	DimAsyncPatterns:      LevelPartial,
}

var typescriptCaps = CapabilityMatrix{
	DimSymbolDefinitions:  LevelPartial,
	DimSymbolReferences:   LevelPartial,
	DimImportResolution:   LevelPartial,
	DimTypeInfo:           LevelPartial,
	DimClassHierarchy:     LevelPartial,
	DimFrameworkSemantics: LevelPartial,
	DimOrmEntityMapping:   LevelPartial,
	DimAuthSecurity:       LevelPartial,
	DimAsyncPatterns:      LevelPartial,
}

var javascriptCaps = CapabilityMatrix{
	DimSymbolDefinitions:  LevelPartial,
	DimSymbolReferences:   LevelPartial,
	DimImportResolution:   LevelPartial,
	DimTypeInfo:           LevelLexicalOnly,
	DimClassHierarchy:     LevelPartial,
	DimFrameworkSemantics: LevelPartial,
	DimOrmEntityMapping:   LevelPartial,
	DimAuthSecurity:       LevelPartial,
	DimAsyncPatterns:      LevelPartial,
}

var pythonCaps = CapabilityMatrix{
	DimSymbolDefinitions:  LevelPartial,
	DimSymbolReferences:   LevelPartial,
	DimImportResolution:   LevelPartial,
	DimTypeInfo:           LevelLexicalOnly,
	DimClassHierarchy:     LevelPartial,
	DimFrameworkSemantics: LevelPartial,
	DimOrmEntityMapping:   LevelPartial,
	DimAuthSecurity:       LevelPartial,
	DimAsyncPatterns:      LevelPartial,
}

var goCaps = CapabilityMatrix{
	DimSymbolDefinitions:  LevelPartial,
	DimSymbolReferences:   LevelPartial,
	DimImportResolution:   LevelPartial,
	DimTypeInfo:           LevelPartial,
	DimClassHierarchy:     LevelLexicalOnly,
	DimFrameworkSemantics: LevelPartial,
	DimOrmEntityMapping:   LevelPartial,
	DimAuthSecurity:       LevelLexicalOnly,
	DimAsyncPatterns:      LevelPartial,
}

var csharpCaps = CapabilityMatrix{
	DimSymbolDefinitions:  LevelPartial,
	DimSymbolReferences:   LevelPartial,
	DimImportResolution:   LevelPartial,
	DimTypeInfo:           LevelPartial,
	DimClassHierarchy:     LevelPartial,
	DimFrameworkSemantics: LevelPartial,
	DimOrmEntityMapping:   LevelPartial,
	DimAuthSecurity:       LevelPartial,
	DimAsyncPatterns:      LevelPartial,
}

var rustCaps = CapabilityMatrix{
	DimSymbolDefinitions:  LevelPartial,
	DimSymbolReferences:   LevelPartial,
	DimImportResolution:   LevelPartial,
	DimTypeInfo:           LevelPartial,
	DimClassHierarchy:     LevelLexicalOnly,
	DimFrameworkSemantics: LevelPartial,
	DimOrmEntityMapping:   LevelUnsupported,
	DimAuthSecurity:       LevelLexicalOnly,
	DimAsyncPatterns:      LevelPartial,
}

var cppCaps = CapabilityMatrix{
	DimSymbolDefinitions:  LevelPartial,
	DimSymbolReferences:   LevelPartial,
	DimImportResolution:   LevelPartial,
	DimTypeInfo:           LevelPartial,
	DimClassHierarchy:     LevelPartial,
	DimFrameworkSemantics: LevelPartial,
	DimOrmEntityMapping:   LevelUnsupported,
	DimAuthSecurity:       LevelLexicalOnly,
	DimAsyncPatterns:      LevelPartial,
}

var lexicalOnlyCaps = CapabilityMatrix{
	DimSymbolDefinitions:  LevelLexicalOnly,
	DimSymbolReferences:   LevelLexicalOnly,
	DimImportResolution:   LevelLexicalOnly,
	DimTypeInfo:           LevelUnsupported,
	DimClassHierarchy:     LevelLexicalOnly,
	DimFrameworkSemantics: LevelLexicalOnly,
	DimOrmEntityMapping:   LevelUnsupported,
	DimAuthSecurity:       LevelLexicalOnly,
	DimAsyncPatterns:      LevelLexicalOnly,
}

var unsupportedCaps = CapabilityMatrix{
	DimSymbolDefinitions:  LevelUnsupported,
	DimSymbolReferences:   LevelUnsupported,
	DimImportResolution:   LevelUnsupported,
	DimTypeInfo:           LevelUnsupported,
	DimClassHierarchy:     LevelUnsupported,
	DimFrameworkSemantics: LevelUnsupported,
	DimOrmEntityMapping:   LevelUnsupported,
	DimAuthSecurity:       LevelUnsupported,
	DimAsyncPatterns:      LevelUnsupported,
}

// lexicalOnlyLanguages enumerates languages whose detectors are regex-only.
// Mirrors LEXICAL_ONLY_LANGUAGES on the Java side.
var lexicalOnlyLanguages = map[string]struct{}{
	"kotlin": {}, "scala": {}, "ruby": {}, "php": {}, "shell": {}, "bash": {},
	"powershell": {}, "markdown": {}, "proto": {}, "hcl": {}, "terraform": {},
	"dockerfile": {}, "yaml": {}, "json": {}, "toml": {}, "ini": {},
	"properties": {}, "xml": {}, "sql": {},
}

// normaliseLanguage trims whitespace and lowercases. Mirrors Java
// CapabilityMatrix#normalise.
func normaliseLanguage(language string) string {
	return strings.ToLower(strings.TrimSpace(language))
}

// CapabilityMatrixFor returns the per-language capability matrix. Returned
// matrix is a defensive copy — callers can mutate without contaminating the
// package-level tables. Mirrors Java CapabilityMatrix#forLanguage.
func CapabilityMatrixFor(language string) CapabilityMatrix {
	src := tableFor(normaliseLanguage(language))
	out := make(CapabilityMatrix, len(src))
	for k, v := range src {
		out[k] = v
	}
	return out
}

// tableFor selects the package-internal CapabilityMatrix for the normalised
// language. Mirrors the Java switch + LEXICAL_ONLY_LANGUAGES / ANTLR_LANGUAGES
// fallback chain.
func tableFor(lang string) CapabilityMatrix {
	switch lang {
	case "java":
		return javaCaps
	case "typescript":
		return typescriptCaps
	case "javascript":
		return javascriptCaps
	case "python":
		return pythonCaps
	case "go":
		return goCaps
	case "csharp", "c#":
		return csharpCaps
	case "cpp", "c++":
		return cppCaps
	case "rust":
		return rustCaps
	default:
		if _, ok := lexicalOnlyLanguages[lang]; ok {
			return lexicalOnlyCaps
		}
		return unsupportedCaps
	}
}

// AllCapabilities returns the matrix for every language with a declared
// table. Keys are normalised lowercase language identifiers; insertion
// order follows the Java side's iteration order (deterministic). The
// returned maps are defensive copies.
func AllCapabilities() map[string]CapabilityMatrix {
	langs := []string{
		"java", "typescript", "javascript", "python", "go", "csharp",
		"rust", "cpp", "kotlin", "scala", "ruby", "php", "shell",
	}
	out := make(map[string]CapabilityMatrix, len(langs))
	for _, lang := range langs {
		out[lang] = CapabilityMatrixFor(lang)
	}
	return out
}
