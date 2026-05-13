// Package jvmhelpers mirrors src/main/java/.../detector/StructuresDetectorHelper.java
// + AbstractJavaMessagingDetector helpers for JVM-family Go detectors.
package jvmhelpers

import (
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// AddImportEdge appends an IMPORTS edge sourced at filePath pointing at target.
// Mirrors StructuresDetectorHelper.addImportEdge.
func AddImportEdge(filePath, target string, edges []*model.CodeEdge) []*model.CodeEdge {
	e := model.NewCodeEdge(filePath+":imports:"+target, model.EdgeImports, filePath, target)
	return append(edges, e)
}

// CreateStructureNode mirrors StructuresDetectorHelper.createStructureNode.
// ID = filePath + ":" + name (matches Java exactly).
func CreateStructureNode(filePath, name string, kind model.NodeKind, lineStart int) *model.CodeNode {
	n := model.NewCodeNode(filePath+":"+name, kind, name)
	n.FQN = name
	n.FilePath = filePath
	n.LineStart = lineStart
	return n
}

// AddExtendsEdge mirrors StructuresDetectorHelper.addExtendsEdge.
// targetKind is the kind for the placeholder reference node (CLASS or INTERFACE).
// The placeholder ID is just targetName (per Java), since the Java helper
// creates a CodeNode with id == targetName for the target reference.
func AddExtendsEdge(sourceNodeID, targetName string, _ model.NodeKind, edges []*model.CodeEdge) []*model.CodeEdge {
	e := model.NewCodeEdge(sourceNodeID+":extends:"+targetName, model.EdgeExtends, sourceNodeID, targetName)
	return append(edges, e)
}

// AddImplementsEdge mirrors StructuresDetectorHelper.addImplementsEdge.
func AddImplementsEdge(sourceNodeID, targetName string, edges []*model.CodeEdge) []*model.CodeEdge {
	e := model.NewCodeEdge(sourceNodeID+":implements:"+targetName, model.EdgeImplements, sourceNodeID, targetName)
	return append(edges, e)
}
