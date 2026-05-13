package python

import (
	"regexp"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// PythonStructuresDetector ports
// io.github.randomcodespace.iq.detector.python.PythonStructuresDetector.
// Phase 4 = regex-only (matches Java's regex fallback path).
type PythonStructuresDetector struct{}

func NewPythonStructuresDetector() *PythonStructuresDetector { return &PythonStructuresDetector{} }

func (PythonStructuresDetector) Name() string                        { return "python_structures" }
func (PythonStructuresDetector) SupportedLanguages() []string        { return []string{"python"} }
func (PythonStructuresDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewPythonStructuresDetector()) }

var (
	pyClassRE       = regexp.MustCompile(`(?m)^class\s+(\w+)(?:\(([^)]*)\))?:`)
	pyFuncRE        = regexp.MustCompile(`(?m)^([^\S\n]*)(async\s+)?def\s+(\w+)\s*\(`)
	pyImportRE      = regexp.MustCompile(`(?m)^(?:from\s+([\w.]+)\s+)?import\s+([\w., ]+)`)
	pyDecoratorRE   = regexp.MustCompile(`(?m)^([^\S\n]*)@(\w[\w.]*)`)
	pyAllRE         = regexp.MustCompile(`(?s)__all__\s*=\s*\[([^\]]*)\]`)
	pyQuotedNameRE  = regexp.MustCompile(`['"](\w+)['"]`)
)

type pyClassRange struct {
	idx    int
	line   int
	indent int
}

func (d PythonStructuresDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath
	moduleName := ctx.ModuleName

	// __all__ exports
	var allExports []string
	allMatchStart := -1
	if am := pyAllRE.FindStringSubmatchIndex(text); am != nil {
		raw := text[am[2]:am[3]]
		for _, qm := range pyQuotedNameRE.FindAllStringSubmatch(raw, -1) {
			allExports = append(allExports, qm[1])
		}
		allMatchStart = am[0]
	}

	// Collect decorators by line.
	decorators := make(map[int][]string)
	for _, m := range pyDecoratorRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		name := text[m[4]:m[5]]
		decorators[line] = append(decorators[line], name)
	}
	findDecorators := func(targetLine int) []string {
		var out []string
		for line := targetLine - 1; ; line-- {
			ds, ok := decorators[line]
			if !ok {
				break
			}
			out = append(out, ds...)
		}
		// Reverse to original source order.
		sort.SliceStable(out, func(i, j int) bool { return false })
		// Manually reverse
		for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
			out[i], out[j] = out[j], out[i]
		}
		return out
	}

	// Walk classes.
	var classNames []string
	var classRanges []pyClassRange
	for _, m := range pyClassRE.FindAllStringSubmatchIndex(text, -1) {
		className := text[m[2]:m[3]]
		var basesStr string
		if m[4] >= 0 {
			basesStr = text[m[4]:m[5]]
		}
		line := base.FindLineNumber(text, m[0])
		// Compute indent
		lineStart := strings.LastIndex(text[:m[0]], "\n") + 1
		indent := m[0] - lineStart

		classNames = append(classNames, className)
		classRanges = append(classRanges, pyClassRange{idx: len(classNames) - 1, line: line, indent: indent})

		annotations := findDecorators(line)
		props := map[string]any{}
		if basesStr != "" && strings.TrimSpace(basesStr) != "" {
			var bs []string
			for _, b := range strings.Split(basesStr, ",") {
				t := strings.TrimSpace(b)
				if t != "" {
					bs = append(bs, t)
				}
			}
			props["bases"] = bs
		}
		if containsString(allExports, className) {
			props["exported"] = true
		}

		nodeID := "py:" + fp + ":class:" + className
		n := model.NewCodeNode(nodeID, model.NodeClass, className)
		n.FQN = className
		n.Module = moduleName
		n.FilePath = fp
		n.LineStart = line
		n.Source = "PythonStructuresDetector"
		n.Confidence = model.ConfidenceLexical
		n.Annotations = annotations
		for k, v := range props {
			n.Properties[k] = v
		}
		nodes = append(nodes, n)

		// EXTENDS edges
		for _, b := range strings.Split(basesStr, ",") {
			t := strings.TrimSpace(b)
			if t == "" {
				continue
			}
			e := model.NewCodeEdge(nodeID+"->extends->"+t, model.EdgeExtends, nodeID, t)
			e.Source = "PythonStructuresDetector"
			e.Confidence = model.ConfidenceLexical
			edges = append(edges, e)
		}
	}

	// Walk functions.
	for _, m := range pyFuncRE.FindAllStringSubmatchIndex(text, -1) {
		indentStr := text[m[2]:m[3]]
		isAsync := m[4] >= 0
		funcName := text[m[6]:m[7]]
		line := base.FindLineNumber(text, m[0])
		indentLen := len(indentStr)

		annotations := findDecorators(line)
		props := map[string]any{}
		if isAsync {
			props["async"] = true
		}
		if containsString(allExports, funcName) {
			props["exported"] = true
		}

		if indentLen == 0 {
			nodeID := "py:" + fp + ":func:" + funcName
			n := model.NewCodeNode(nodeID, model.NodeMethod, funcName)
			n.FQN = funcName
			n.Module = moduleName
			n.FilePath = fp
			n.LineStart = line
			n.Source = "PythonStructuresDetector"
			n.Confidence = model.ConfidenceLexical
			n.Annotations = annotations
			for k, v := range props {
				n.Properties[k] = v
			}
			nodes = append(nodes, n)
		} else {
			enclosing := findEnclosingClass(classNames, classRanges, line, indentLen)
			if enclosing != "" {
				nodeID := "py:" + fp + ":class:" + enclosing + ":method:" + funcName
				n := model.NewCodeNode(nodeID, model.NodeMethod, enclosing+"."+funcName)
				n.FQN = enclosing + "." + funcName
				n.Module = moduleName
				n.FilePath = fp
				n.LineStart = line
				n.Source = "PythonStructuresDetector"
				n.Confidence = model.ConfidenceLexical
				n.Annotations = annotations
				props["class"] = enclosing
				for k, v := range props {
					n.Properties[k] = v
				}
				nodes = append(nodes, n)

				classID := "py:" + fp + ":class:" + enclosing
				e := model.NewCodeEdge(classID+"->defines->"+nodeID, model.EdgeDefines, classID, nodeID)
				e.Source = "PythonStructuresDetector"
				e.Confidence = model.ConfidenceLexical
				edges = append(edges, e)
			}
		}
	}

	// Imports — emit anchor nodes so the edges survive the GraphBuilder
	// phantom-drop. Pre-fix, both endpoints were free-form strings (file
	// path and module name) with no matching CodeNode. The dedup map
	// collapses the per-file external nodes across files (every file
	// importing `requests` gets one shared py:external:requests target).
	fileModuleID := "py:file:" + fp
	importSeen := make(map[string]bool)
	ensureFile := func() {
		if !importSeen["__file__"] {
			importSeen["__file__"] = true
			fn := model.NewCodeNode(fileModuleID, model.NodeModule, fp)
			fn.FilePath = fp
			fn.Source = "PythonStructuresDetector"
			fn.Confidence = model.ConfidenceLexical
			fn.Properties["module_type"] = "py_file"
			nodes = append(nodes, fn)
		}
	}
	emitImport := func(mod string) {
		mod = strings.TrimSpace(mod)
		if mod == "" || importSeen["mod:"+mod] {
			return
		}
		importSeen["mod:"+mod] = true
		ensureFile()
		targetID := "py:external:" + mod
		if !importSeen["tgt:"+mod] {
			importSeen["tgt:"+mod] = true
			tn := model.NewCodeNode(targetID, model.NodeExternal, mod)
			tn.Source = "PythonStructuresDetector"
			tn.Confidence = model.ConfidenceLexical
			tn.Properties["module"] = mod
			nodes = append(nodes, tn)
		}
		e := model.NewCodeEdge(fileModuleID+"->imports->"+targetID, model.EdgeImports, fileModuleID, targetID)
		e.Source = "PythonStructuresDetector"
		e.Confidence = model.ConfidenceLexical
		edges = append(edges, e)
	}
	for _, m := range pyImportRE.FindAllStringSubmatchIndex(text, -1) {
		var fromMod string
		if m[2] >= 0 {
			fromMod = text[m[2]:m[3]]
		}
		if fromMod != "" {
			emitImport(fromMod)
			continue
		}
		importNames := text[m[4]:m[5]]
		for _, n := range strings.Split(importNames, ",") {
			emitImport(n)
		}
	}

	// __all__ module node
	if allExports != nil {
		moduleNodeID := "py:" + fp + ":module"
		mn := model.NewCodeNode(moduleNodeID, model.NodeModule, fp)
		mn.FQN = fp
		mn.Module = moduleName
		mn.FilePath = fp
		mn.LineStart = base.FindLineNumber(text, allMatchStart)
		mn.Source = "PythonStructuresDetector"
		mn.Confidence = model.ConfidenceLexical
		mn.Properties["__all__"] = allExports
		nodes = append(nodes, mn)
	}

	return detector.ResultOf(nodes, edges)
}

func containsString(s []string, v string) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}

func findEnclosingClass(names []string, ranges []pyClassRange, line, funcIndent int) string {
	for i := len(ranges) - 1; i >= 0; i-- {
		r := ranges[i]
		if line > r.line && funcIndent > r.indent {
			return names[r.idx]
		}
	}
	return ""
}
