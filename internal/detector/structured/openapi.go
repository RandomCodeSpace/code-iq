package structured

import (
	"fmt"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// OpenApiDetector mirrors Java OpenApiDetector. Emits a CONFIG_FILE for the
// API spec, ENDPOINT per (path, method) pair, and ENTITY per schema (under
// components.schemas or definitions). DEPENDS_ON edges follow $ref strings
// between schemas.
type OpenApiDetector struct{}

func NewOpenApiDetector() *OpenApiDetector { return &OpenApiDetector{} }

func (OpenApiDetector) Name() string                        { return "openapi" }
func (OpenApiDetector) SupportedLanguages() []string        { return []string{"json", "yaml"} }
func (OpenApiDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewOpenApiDetector()) }

var openAPIMethods = map[string]bool{
	"get": true, "post": true, "put": true, "patch": true,
	"delete": true, "head": true, "options": true, "trace": true,
}

func (d OpenApiDetector) Detect(ctx *detector.Context) *detector.Result {
	if ctx.ParsedData == nil {
		return detector.EmptyResult()
	}
	spec := base.GetMap(ctx.ParsedData, "data")
	if len(spec) == 0 {
		return detector.EmptyResult()
	}
	_, hasOpenAPI := spec["openapi"]
	_, hasSwagger := spec["swagger"]
	if !hasOpenAPI && !hasSwagger {
		return detector.EmptyResult()
	}
	fp := ctx.FilePath
	configID := "api:" + fp
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	info := base.GetMap(spec, "info")
	apiTitle := base.GetString(info, "title")
	if apiTitle == "" {
		apiTitle = fp
	}
	apiVersion := base.GetStringOrDefault(info, "version", "")
	specVer := ""
	if v, ok := spec["openapi"]; ok {
		specVer = fmt.Sprint(v)
	} else if v, ok := spec["swagger"]; ok {
		specVer = fmt.Sprint(v)
	}
	cn := model.NewCodeNode(configID, model.NodeConfigFile, apiTitle)
	cn.FQN = fp
	cn.Module = ctx.ModuleName
	cn.FilePath = fp
	cn.Confidence = base.StructuredDetectorDefaultConfidence
	cn.Properties["config_type"] = "openapi"
	cn.Properties["api_title"] = apiTitle
	cn.Properties["api_version"] = apiVersion
	cn.Properties["spec_version"] = specVer
	nodes = append(nodes, cn)

	// Endpoints
	paths := base.GetMap(spec, "paths")
	pathKeys := mapKeysSorted(paths)
	for _, p := range pathKeys {
		pathItem := base.AsMap(paths[p])
		methodKeys := mapKeysSorted(pathItem)
		for _, method := range methodKeys {
			if !openAPIMethods[strings.ToLower(method)] {
				continue
			}
			methodUpper := strings.ToUpper(method)
			endpointID := "api:" + fp + ":" + strings.ToLower(method) + ":" + p
			en := model.NewCodeNode(endpointID, model.NodeEndpoint, methodUpper+" "+p)
			en.Module = ctx.ModuleName
			en.FilePath = fp
			en.Confidence = base.StructuredDetectorDefaultConfidence
			en.Properties["http_method"] = methodUpper
			en.Properties["path"] = p
			operation := base.AsMap(pathItem[method])
			if opID := base.GetString(operation, "operationId"); opID != "" {
				en.Properties["operation_id"] = opID
			}
			if sm := base.GetString(operation, "summary"); sm != "" {
				en.Properties["summary"] = sm
			}
			nodes = append(nodes, en)
			edges = append(edges, model.NewCodeEdge(configID+"->"+endpointID,
				model.EdgeContains, configID, endpointID))
		}
	}

	// Schemas
	schemas := extractOpenAPISchemas(spec)
	schemaNames := mapKeysSorted(schemas)
	for _, schemaName := range schemaNames {
		schemaID := "api:" + fp + ":schema:" + schemaName
		schemaDef := base.AsMap(schemas[schemaName])
		sn := model.NewCodeNode(schemaID, model.NodeEntity, schemaName)
		sn.Module = ctx.ModuleName
		sn.FilePath = fp
		sn.Confidence = base.StructuredDetectorDefaultConfidence
		sn.Properties["schema_name"] = schemaName
		if t := base.GetString(schemaDef, "type"); t != "" {
			sn.Properties["schema_type"] = t
		}
		nodes = append(nodes, sn)
		edges = append(edges, model.NewCodeEdge(configID+"->"+schemaID,
			model.EdgeContains, configID, schemaID))

		// $ref edges
		refs := collectOpenAPIRefs(schemas[schemaName])
		seenLocal := map[string]bool{}
		for _, ref := range refs {
			refName := refToSchemaName(ref)
			if refName == "" || refName == schemaName || seenLocal[refName] {
				continue
			}
			if _, ok := schemas[refName]; !ok {
				continue
			}
			seenLocal[refName] = true
			edges = append(edges, model.NewCodeEdge(
				schemaID+"->api:"+fp+":schema:"+refName,
				model.EdgeDependsOn,
				schemaID,
				"api:"+fp+":schema:"+refName,
			))
		}
	}
	return detector.ResultOf(nodes, edges)
}

func extractOpenAPISchemas(spec map[string]any) map[string]any {
	if comps := base.GetMap(spec, "components"); comps != nil {
		if s := base.GetMap(comps, "schemas"); len(s) > 0 {
			return s
		}
	}
	if defs := base.GetMap(spec, "definitions"); len(defs) > 0 {
		return defs
	}
	return map[string]any{}
}

func collectOpenAPIRefs(obj any) []string {
	out := []string{}
	var walk func(v any)
	walk = func(v any) {
		switch t := v.(type) {
		case map[string]any:
			if r, ok := t["$ref"].(string); ok {
				out = append(out, r)
			}
			keys := mapKeysSorted(t)
			for _, k := range keys {
				walk(t[k])
			}
		case []any:
			for _, e := range t {
				walk(e)
			}
		}
	}
	walk(obj)
	return out
}

func refToSchemaName(ref string) string {
	if !strings.HasPrefix(ref, "#/") {
		return ""
	}
	parts := strings.Split(ref, "/")
	if len(parts) < 2 {
		return ""
	}
	return parts[len(parts)-1]
}
