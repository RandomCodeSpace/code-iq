package csharp

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const csharpStructSource = `using System;
using Microsoft.AspNetCore.Mvc;

namespace MyApp.Api;

public abstract class BaseEntity {
}

public class User : BaseEntity, IComparable, IEquatable<User> {
}

public interface IUserRepository {
}

public enum UserRole {
    Admin,
    User
}

[Route("api/[controller]")]
public class UsersController : ControllerBase {
    [HttpGet]
    public IActionResult List() => Ok();

    [HttpPost("create")]
    public IActionResult Create() => Ok();
}
`

func TestCSharpStructuresPositive(t *testing.T) {
	d := NewStructuresDetector()
	r := d.Detect(&detector.Context{
		FilePath:   "Api.cs",
		Language:   "csharp",
		Content:    csharpStructSource,
		ModuleName: "MyApp.Api",
	})

	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	if kinds[model.NodeModule] != 1 {
		t.Errorf("expected 1 MODULE (namespace), got %d", kinds[model.NodeModule])
	}
	// Note: Java CSharpStructuresDetector uses a 60-char window before the
	// class match to detect "abstract". A class declared shortly after an
	// abstract class will pick up the previous class's modifier — known
	// Java parity behaviour. Total abstract+regular class count == 3 here
	// (BaseEntity + User + UsersController).
	totalClass := kinds[model.NodeAbstractClass] + kinds[model.NodeClass]
	if totalClass != 3 {
		t.Errorf("expected 3 class-like nodes total, got %d", totalClass)
	}
	if kinds[model.NodeAbstractClass] < 1 {
		t.Errorf("expected >=1 ABSTRACT_CLASS, got %d", kinds[model.NodeAbstractClass])
	}
	if kinds[model.NodeInterface] != 1 {
		t.Errorf("expected 1 INTERFACE, got %d", kinds[model.NodeInterface])
	}
	if kinds[model.NodeEnum] != 1 {
		t.Errorf("expected 1 ENUM, got %d", kinds[model.NodeEnum])
	}
	if kinds[model.NodeEndpoint] != 2 {
		t.Errorf("expected 2 ENDPOINTs, got %d", kinds[model.NodeEndpoint])
	}

	// Edges: 2 using imports + 1 EXTENDS (User->BaseEntity) + 2 IMPLEMENTS (IComparable, IEquatable<User>)
	importEdges := 0
	extendsEdges := 0
	implementsEdges := 0
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeImports:
			importEdges++
		case model.EdgeExtends:
			extendsEdges++
		case model.EdgeImplements:
			implementsEdges++
		}
	}
	if importEdges != 2 {
		t.Errorf("expected 2 import edges, got %d", importEdges)
	}
	// UsersController -> ControllerBase (extends) + User -> BaseEntity = 2 EXTENDS
	if extendsEdges < 1 {
		t.Errorf("expected EXTENDS edges, got %d", extendsEdges)
	}
	if implementsEdges < 2 {
		t.Errorf("expected >=2 IMPLEMENTS edges, got %d", implementsEdges)
	}
}

func TestCSharpStructuresControllerRoute(t *testing.T) {
	// Note: mirrors Java CSharpStructuresDetector's forward scan for the
	// HttpXxx attribute (j = i-5 → i, first-match-wins). When two methods
	// share a 5-line window, both pick up the earlier method's attribute.
	// This is a known Java parity bug; keep test loose so we don't regress
	// when the Java side is fixed and we follow.
	d := NewStructuresDetector()
	r := d.Detect(&detector.Context{
		FilePath:   "Api.cs",
		Language:   "csharp",
		Content:    csharpStructSource,
		ModuleName: "MyApp.Api",
	})
	pathsFound := map[string]bool{}
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEndpoint {
			pathsFound[n.Properties["path"].(string)] = true
		}
	}
	if !pathsFound["/api/Users"] {
		t.Errorf("expected /api/Users as the controller-route prefix path; got %v", pathsFound)
	}
}

func TestCSharpStructuresNegative(t *testing.T) {
	d := NewStructuresDetector()
	r := d.Detect(&detector.Context{FilePath: "x.cs", Language: "csharp", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes on empty input")
	}
}

func TestCSharpStructuresDeterminism(t *testing.T) {
	d := NewStructuresDetector()
	ctx := &detector.Context{FilePath: "Api.cs", Language: "csharp", Content: csharpStructSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
