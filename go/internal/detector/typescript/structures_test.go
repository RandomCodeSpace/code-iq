package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const tsStructuresSource = `import { useState } from 'react';
import * as fs from 'fs';

export interface User {
    id: string;
    name: string;
}

export type Maybe<T> = T | null;

export enum Status {
    Active,
    Inactive,
}

export class UserService {
    findById(id: string): User { return null!; }
}

export async function fetchUser(id: string): Promise<User> {
    return null!;
}

export const sum = async (a: number, b: number) => a + b;

export namespace Util {
    export function noop() {}
}
`

func TestTypeScriptStructuresPositive(t *testing.T) {
	d := NewTypeScriptStructuresDetector()
	ctx := &detector.Context{
		FilePath: "src/x.ts",
		Language: "typescript",
		Content:  tsStructuresSource,
	}
	r := d.Detect(ctx)
	var ifaces, classes, methods, enums, modules int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeInterface:
			ifaces++
		case model.NodeClass:
			classes++
		case model.NodeMethod:
			methods++
		case model.NodeEnum:
			enums++
		case model.NodeModule:
			modules++
		}
	}
	if ifaces != 1 {
		t.Errorf("expected 1 interface, got %d", ifaces)
	}
	// 1 class + 1 type alias (also CLASS kind in Java semantics)
	if classes != 2 {
		t.Errorf("expected 2 classes (incl type alias), got %d", classes)
	}
	if methods < 2 {
		t.Errorf("expected >= 2 methods, got %d", methods)
	}
	if enums != 1 {
		t.Errorf("expected 1 enum, got %d", enums)
	}
	// 1 namespace + 1 file-as-module (anchor for imports edges so they
	// survive the GraphBuilder phantom-drop).
	if modules != 2 {
		t.Errorf("expected 2 modules (namespace + file), got %d", modules)
	}
	if len(r.Edges) != 2 {
		t.Errorf("expected 2 imports, got %d", len(r.Edges))
	}
	// Each import target should also exist as an EXTERNAL node so the
	// edge isn't dropped at snapshot.
	var externals int
	for _, n := range r.Nodes {
		if n.Kind == model.NodeExternal {
			externals++
		}
	}
	if externals != 2 {
		t.Errorf("expected 2 external module nodes (imports targets), got %d", externals)
	}
}

func TestTypeScriptStructuresDeterminism(t *testing.T) {
	d := NewTypeScriptStructuresDetector()
	ctx := &detector.Context{FilePath: "src/x.ts", Language: "typescript", Content: tsStructuresSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
	sort.Slice(r1.Nodes, func(i, j int) bool { return r1.Nodes[i].ID < r1.Nodes[j].ID })
	sort.Slice(r2.Nodes, func(i, j int) bool { return r2.Nodes[i].ID < r2.Nodes[j].ID })
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic at %d", i)
		}
	}
}
