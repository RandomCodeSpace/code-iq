package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
)

const gqlSample = `import org.springframework.graphql.data.method.annotation.*;

@Controller
public class UserResolver {
    @QueryMapping
    public User user(@Argument String id) { return null; }

    @MutationMapping("createUser")
    public User createUser(@Argument String name) { return null; }

    @SubscriptionMapping
    public Publisher<Event> events() { return null; }

    @SchemaMapping(typeName = "User")
    public String displayName(User u) { return u.name; }
}

@DgsComponent
public class DgsResolver {
    @DgsQuery(field = "currentUser")
    public User current() { return null; }

    @DgsData(parentType = "Order", field = "total")
    public Money total(Order o) { return null; }
}`

func TestGraphqlResolverPositive(t *testing.T) {
	d := NewJavaGraphqlResolverDetector()
	r := d.Detect(&detector.Context{FilePath: "src/UserResolver.java", Language: "java", Content: gqlSample})
	if len(r.Nodes) < 4 {
		t.Fatalf("expected >=4 resolver nodes, got %d", len(r.Nodes))
	}
	types := map[string]bool{}
	for _, n := range r.Nodes {
		if v, _ := n.Properties["graphql_type"].(string); v != "" {
			types[v] = true
		}
	}
	for _, want := range []string{"Query", "Mutation", "Subscription"} {
		if !types[want] {
			t.Errorf("missing graphql_type=%s", want)
		}
	}
}

func TestGraphqlResolverNegative(t *testing.T) {
	d := NewJavaGraphqlResolverDetector()
	r := d.Detect(&detector.Context{FilePath: "src/X.java", Language: "java", Content: "class X {}"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0, got %d", len(r.Nodes))
	}
}

func TestGraphqlResolverDeterminism(t *testing.T) {
	d := NewJavaGraphqlResolverDetector()
	c := &detector.Context{FilePath: "src/X.java", Language: "java", Content: gqlSample}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
