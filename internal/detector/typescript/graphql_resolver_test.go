package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const gqlSource = `import { Resolver, Query, Mutation } from '@nestjs/graphql';

@Resolver(of => User)
export class UserResolver {
    @Query(() => [User])
    async users() { return []; }

    @Mutation(() => User)
    async createUser(@Args() args) { return null; }
}
`

const gqlSchemaSource = `type Query {
    users: [User]
    user(id: ID!): User
}

type Mutation {
    addUser(input: UserInput): User
}
`

func TestGraphQLResolverPositive(t *testing.T) {
	d := NewGraphQLResolverDetector()
	ctx := &detector.Context{
		FilePath: "src/user.resolver.ts",
		Language: "typescript",
		Content:  gqlSource,
	}
	r := d.Detect(ctx)
	var classes, endpoints int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeClass:
			classes++
		case model.NodeEndpoint:
			endpoints++
		}
	}
	if classes != 1 {
		t.Errorf("expected 1 class, got %d", classes)
	}
	if endpoints != 2 {
		t.Errorf("expected 2 endpoints, got %d", endpoints)
	}
}

func TestGraphQLResolverSchemaPositive(t *testing.T) {
	d := NewGraphQLResolverDetector()
	ctx := &detector.Context{
		FilePath: "schema.graphql",
		Language: "typescript",
		Content:  gqlSchemaSource,
	}
	r := d.Detect(ctx)
	if len(r.Nodes) < 3 {
		t.Errorf("expected 3+ endpoints from schema, got %d", len(r.Nodes))
	}
}

func TestGraphQLResolverDeterminism(t *testing.T) {
	d := NewGraphQLResolverDetector()
	ctx := &detector.Context{FilePath: "src/x.ts", Language: "typescript", Content: gqlSource}
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
