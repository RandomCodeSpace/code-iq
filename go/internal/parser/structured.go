package parser

import (
	"bufio"
	"encoding/json"
	"strings"

	yaml "gopkg.in/yaml.v3"
)

// ParsedEnvelope wraps a structured parse result in the same envelope shape
// the Java side uses (a Map<String, Object> with keys "type" and either
// "data" or "documents"). It is a typed alias for clarity; detectors consume
// it as a plain map[string]any (see detector.Context.ParsedData).
type ParsedEnvelope = map[string]any

// ParseStructured dispatches to the right structured parser based on
// Language. Returns nil for languages this parser does not handle. Errors
// are returned for true parse failures; an empty / non-applicable input
// yields ({"type":"yaml","data":{}}, nil) rather than nil/error.
func ParseStructured(lang Language, source []byte) (ParsedEnvelope, error) {
	switch lang {
	case LanguageYaml:
		return parseYAML(source)
	case LanguageJSON:
		return parseJSON(source)
	case LanguageTOML:
		return parseTOML(source), nil
	case LanguageINI:
		return parseINI(source), nil
	case LanguageProperties:
		return parseProperties(source), nil
	}
	return nil, nil
}

// parseYAML parses a YAML document into the envelope shape. Multi-document
// YAML produces {"type":"yaml_multi","documents":[map,map,...]} ; a single
// document produces {"type":"yaml","data":map}.
func parseYAML(source []byte) (ParsedEnvelope, error) {
	dec := yaml.NewDecoder(strings.NewReader(string(source)))
	var docs []any
	for {
		var doc any
		if err := dec.Decode(&doc); err != nil {
			if err.Error() == "EOF" {
				break
			}
			// Best-effort: skip bad documents and continue.
			break
		}
		if doc != nil {
			docs = append(docs, normalizeYAML(doc))
		}
	}
	if len(docs) == 0 {
		return ParsedEnvelope{"type": "yaml", "data": map[string]any{}}, nil
	}
	if len(docs) == 1 {
		data, _ := docs[0].(map[string]any)
		if data == nil {
			data = map[string]any{}
		}
		return ParsedEnvelope{"type": "yaml", "data": data}, nil
	}
	return ParsedEnvelope{"type": "yaml_multi", "documents": docs}, nil
}

// normalizeYAML converts yaml.v3's map[interface{}]interface{} into
// map[string]any recursively. The default yaml.v3 unmarshal into any uses
// string keys already (unlike yaml.v2), but we still coerce bare booleans
// like `on:` / `off:` / `yes:` / `no:` back to their string form because
// Kubernetes / GitHub Actions YAMLs use them as keys and parsers downstream
// expect string keys.
func normalizeYAML(v any) any {
	switch x := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(x))
		for k, val := range x {
			out[k] = normalizeYAML(val)
		}
		return out
	case map[any]any:
		out := make(map[string]any, len(x))
		for k, val := range x {
			out[stringifyKey(k)] = normalizeYAML(val)
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, e := range x {
			out[i] = normalizeYAML(e)
		}
		return out
	}
	return v
}

func stringifyKey(k any) string {
	switch v := k.(type) {
	case string:
		return v
	case bool:
		// SnakeYAML / yaml.v3 parses bare `on` / `off` / `yes` / `no` as
		// booleans. Coerce back to the canonical lowercase string so callers
		// can do string comparisons (GitHub Actions workflows use `on:`).
		if v {
			return "true"
		}
		return "false"
	default:
		// Fall back to fmt.Sprint behaviour via the type-switch default.
		// We avoid an fmt import in the hot path by handling common types.
		return jsonScalarString(k)
	}
}

func jsonScalarString(v any) string {
	// Cheap stringification covering int/float/nil cases. Avoids fmt.
	b, err := json.Marshal(v)
	if err != nil {
		return ""
	}
	s := string(b)
	if len(s) >= 2 && s[0] == '"' && s[len(s)-1] == '"' {
		return s[1 : len(s)-1]
	}
	return s
}

// parseJSON unmarshals JSON content into the envelope shape. Non-object
// top-levels (arrays, scalars) yield {"type":"json","data":{}} rather than
// an error.
func parseJSON(source []byte) (ParsedEnvelope, error) {
	if len(strings.TrimSpace(string(source))) == 0 {
		return ParsedEnvelope{"type": "json", "data": map[string]any{}}, nil
	}
	var root any
	if err := json.Unmarshal(source, &root); err != nil {
		return ParsedEnvelope{"type": "json", "data": map[string]any{}}, nil
	}
	data, ok := root.(map[string]any)
	if !ok {
		data = map[string]any{}
	}
	return ParsedEnvelope{"type": "json", "data": data}, nil
}

// parseTOML implements minimal TOML parsing sufficient for the structured
// detectors: top-level key = value pairs and [section] / [section.sub]
// tables. No support for arrays-of-tables, inline tables, or multiline
// strings — the detectors only need section/key shape. The Java side uses
// SnakeYAML's TOML mode which is similarly shallow.
func parseTOML(source []byte) ParsedEnvelope {
	data := map[string]any{}
	var currentSection string
	sc := bufio.NewScanner(strings.NewReader(string(source)))
	sc.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for sc.Scan() {
		raw := strings.TrimSpace(sc.Text())
		if raw == "" || strings.HasPrefix(raw, "#") {
			continue
		}
		if strings.HasPrefix(raw, "[") && strings.HasSuffix(raw, "]") {
			section := unquote(strings.TrimSpace(raw[1 : len(raw)-1]))
			currentSection = section
			// Walk into a nested map; only create the top-level section in
			// data — nested namespacing is preserved by the dotted key.
			top := topLevelOf(section)
			if _, ok := data[top]; !ok {
				data[top] = map[string]any{}
			}
			continue
		}
		eq := strings.Index(raw, "=")
		if eq <= 0 {
			continue
		}
		key := unquote(strings.TrimSpace(raw[:eq]))
		val := strings.TrimSpace(raw[eq+1:])
		val = unquote(val)
		if currentSection == "" {
			data[key] = val
		} else {
			top := topLevelOf(currentSection)
			sub, ok := data[top].(map[string]any)
			if !ok {
				sub = map[string]any{}
				data[top] = sub
			}
			sub[key] = val
		}
	}
	return ParsedEnvelope{"type": "toml", "data": data}
}

func topLevelOf(section string) string {
	if i := strings.IndexByte(section, '.'); i >= 0 {
		return section[:i]
	}
	return section
}

func unquote(s string) string {
	if len(s) >= 2 && (s[0] == '"' && s[len(s)-1] == '"' || s[0] == '\'' && s[len(s)-1] == '\'') {
		return s[1 : len(s)-1]
	}
	return s
}

// parseINI implements minimal INI parsing: [section] headers and key = value
// pairs grouped under their section.
func parseINI(source []byte) ParsedEnvelope {
	data := map[string]any{}
	var currentSection string
	sc := bufio.NewScanner(strings.NewReader(string(source)))
	sc.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for sc.Scan() {
		raw := strings.TrimSpace(sc.Text())
		if raw == "" || strings.HasPrefix(raw, "#") || strings.HasPrefix(raw, ";") {
			continue
		}
		if strings.HasPrefix(raw, "[") && strings.HasSuffix(raw, "]") {
			currentSection = strings.TrimSpace(raw[1 : len(raw)-1])
			if _, ok := data[currentSection]; !ok {
				data[currentSection] = map[string]any{}
			}
			continue
		}
		if currentSection == "" {
			continue // INI requires a section in this shallow parser
		}
		eq := strings.Index(raw, "=")
		if eq <= 0 {
			continue
		}
		key := strings.TrimSpace(raw[:eq])
		val := strings.TrimSpace(raw[eq+1:])
		sect := data[currentSection].(map[string]any)
		sect[key] = val
	}
	return ParsedEnvelope{"type": "ini", "data": data}
}

// parseProperties implements minimal .properties parsing: key=value pairs,
// '#' and '!' comments, trim whitespace around the separator. Mirrors the
// Java side's PropertiesLoader subset.
func parseProperties(source []byte) ParsedEnvelope {
	data := map[string]any{}
	sc := bufio.NewScanner(strings.NewReader(string(source)))
	sc.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for sc.Scan() {
		raw := strings.TrimSpace(sc.Text())
		if raw == "" || strings.HasPrefix(raw, "#") || strings.HasPrefix(raw, "!") {
			continue
		}
		// Java .properties accepts '=', ':' and whitespace as separators.
		idx := strings.IndexAny(raw, "=:")
		if idx <= 0 {
			// Whitespace-separated key/value
			if i := strings.IndexAny(raw, " \t"); i > 0 {
				key := strings.TrimSpace(raw[:i])
				val := strings.TrimSpace(raw[i+1:])
				if key != "" {
					data[key] = val
				}
			}
			continue
		}
		key := strings.TrimSpace(raw[:idx])
		val := strings.TrimSpace(raw[idx+1:])
		if key != "" {
			data[key] = val
		}
	}
	return ParsedEnvelope{"type": "properties", "data": data}
}
