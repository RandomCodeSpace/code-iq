package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const springSecuritySample = `@EnableWebSecurity
public class SecurityConfig {
    @Secured("ROLE_ADMIN")
    public void adminOnly() {}
    @PreAuthorize("hasRole('USER')")
    public void userOnly() {}
    @RolesAllowed({"ROLE_X", "ROLE_Y"})
    public void multi() {}
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http.authorizeHttpRequests(req -> req.anyRequest().authenticated());
        return null;
    }
}
`

func TestSpringSecurityPositive(t *testing.T) {
	d := NewSpringSecurityDetector()
	ctx := &detector.Context{FilePath: "src/SecurityConfig.java", Language: "java", Content: springSecuritySample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	var hasEnable, hasSecured, hasPre, hasRoles, hasFilterChain, hasAuthorize bool
	for _, n := range r.Nodes {
		switch n.Label {
		case "@EnableWebSecurity":
			hasEnable = true
		case "@Secured":
			hasSecured = true
		case "@PreAuthorize":
			hasPre = true
		case "@RolesAllowed":
			hasRoles = true
		case ".authorizeHttpRequests()":
			hasAuthorize = true
		}
		if n.Label == "SecurityFilterChain:filterChain" {
			hasFilterChain = true
		}
	}
	if !hasEnable {
		t.Error("missing @EnableWebSecurity")
	}
	if !hasSecured {
		t.Error("missing @Secured")
	}
	if !hasPre {
		t.Error("missing @PreAuthorize")
	}
	if !hasRoles {
		t.Error("missing @RolesAllowed")
	}
	if !hasFilterChain {
		t.Error("missing SecurityFilterChain node")
	}
	if !hasAuthorize {
		t.Error("missing .authorizeHttpRequests()")
	}
	for _, n := range r.Nodes {
		if n.Properties["framework"] != "spring_boot" {
			t.Errorf("node %q missing framework=spring_boot", n.Label)
		}
		if n.Kind != model.NodeGuard {
			t.Errorf("expected Guard kind, got %v", n.Kind)
		}
	}
}

func TestSpringSecurityRolesExtracted(t *testing.T) {
	d := NewSpringSecurityDetector()
	ctx := &detector.Context{FilePath: "src/SecurityConfig.java", Language: "java", Content: springSecuritySample}
	r := d.Detect(ctx)
	// @PreAuthorize("hasRole('USER')") → roles = ["USER"]
	for _, n := range r.Nodes {
		if n.Label != "@PreAuthorize" {
			continue
		}
		roles, ok := n.Properties["roles"].([]string)
		if !ok || len(roles) != 1 || roles[0] != "USER" {
			t.Errorf("@PreAuthorize roles wrong: %v", n.Properties["roles"])
		}
	}
}

func TestSpringSecurityNegative(t *testing.T) {
	d := NewSpringSecurityDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestSpringSecurityDeterminism(t *testing.T) {
	d := NewSpringSecurityDetector()
	ctx := &detector.Context{FilePath: "src/SecurityConfig.java", Language: "java", Content: springSecuritySample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic count")
	}
}
