package auth

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestLdapJavaPositive(t *testing.T) {
	d := NewLdapAuthDetector()
	src := `import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;

@Bean
public LdapContextSource contextSource() {
    return new LdapContextSource();
}

@Bean
public LdapTemplate ldapTemplate() {
    return new LdapTemplate(contextSource());
}
`
	r := d.Detect(&detector.Context{FilePath: "Auth.java", Language: "java", Content: src})
	guards := 0
	for _, n := range r.Nodes {
		if n.Kind == model.NodeGuard {
			guards++
			if n.Properties["auth_type"] != "ldap" {
				t.Errorf("auth_type = %v", n.Properties["auth_type"])
			}
			if n.Properties["language"] != "java" {
				t.Errorf("language = %v", n.Properties["language"])
			}
		}
	}
	if guards < 2 {
		t.Errorf("expected >=2 GUARD, got %d", guards)
	}
}

func TestLdapPython(t *testing.T) {
	d := NewLdapAuthDetector()
	src := `import ldap3
server = ldap3.Server('ldap://example.com')
conn = ldap3.Connection(server, user='cn=admin', password='secret')
`
	r := d.Detect(&detector.Context{FilePath: "auth.py", Language: "python", Content: src})
	if len(r.Nodes) < 2 {
		t.Errorf("expected >=2 GUARD, got %d", len(r.Nodes))
	}
}

func TestLdapTypescript(t *testing.T) {
	d := NewLdapAuthDetector()
	src := `const ldap = require('ldapjs');
const passportLdap = require('passport-ldapauth');
`
	r := d.Detect(&detector.Context{FilePath: "auth.ts", Language: "typescript", Content: src})
	if len(r.Nodes) < 1 {
		t.Error("expected >=1 GUARD")
	}
}

func TestLdapCsharp(t *testing.T) {
	d := NewLdapAuthDetector()
	src := `using System.DirectoryServices;
var entry = new DirectoryEntry("LDAP://example.com");
`
	r := d.Detect(&detector.Context{FilePath: "Auth.cs", Language: "csharp", Content: src})
	if len(r.Nodes) < 1 {
		t.Error("expected >=1 GUARD")
	}
}

func TestLdapUnsupportedLanguage(t *testing.T) {
	d := NewLdapAuthDetector()
	r := d.Detect(&detector.Context{FilePath: "x.rs", Language: "rust", Content: "LdapTemplate"})
	if len(r.Nodes) != 0 {
		t.Error("rust not supported — expect 0")
	}
}

func TestLdapNegative(t *testing.T) {
	d := NewLdapAuthDetector()
	r := d.Detect(&detector.Context{FilePath: "x.java", Language: "java", Content: "// no auth here"})
	if len(r.Nodes) != 0 {
		t.Error("expected 0 nodes when no auth keyword")
	}
}

func TestLdapDeterminism(t *testing.T) {
	d := NewLdapAuthDetector()
	ctx := &detector.Context{FilePath: "Auth.java", Language: "java",
		Content: "LdapContextSource ctx;\nLdapTemplate tpl;\n"}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
