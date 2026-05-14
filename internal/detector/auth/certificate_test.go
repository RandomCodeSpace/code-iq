package auth

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
)

func TestCertificateMTLS(t *testing.T) {
	d := NewCertificateAuthDetector()
	src := `ssl_verify_client on;
clientAuth="true"
`
	r := d.Detect(&detector.Context{FilePath: "nginx.conf", Language: "yaml", Content: src})
	if len(r.Nodes) < 2 {
		t.Errorf("expected >=2 mtls guards, got %d", len(r.Nodes))
	}
	for _, n := range r.Nodes {
		if n.Properties["auth_type"] != "mtls" {
			t.Errorf("auth_type = %v, want mtls", n.Properties["auth_type"])
		}
	}
}

func TestCertificateX509(t *testing.T) {
	d := NewCertificateAuthDetector()
	src := `import org.springframework.security.web.authentication.preauth.x509.X509AuthenticationFilter;
http.x509();
`
	r := d.Detect(&detector.Context{FilePath: "Sec.java", Language: "java", Content: src})
	found := false
	for _, n := range r.Nodes {
		if n.Properties["auth_type"] == "x509" {
			found = true
		}
	}
	if !found {
		t.Error("expected x509 guard")
	}
}

func TestCertificateAzureAd(t *testing.T) {
	d := NewCertificateAuthDetector()
	src := `var tenantId = AZURE_TENANT_ID="abc123-def456";
var cred = new ClientCertificateCredential();
`
	r := d.Detect(&detector.Context{FilePath: "Auth.cs", Language: "csharp", Content: src})
	azureFound := false
	clientCertFlowFound := false
	tenantFound := false
	for _, n := range r.Nodes {
		if n.Properties["auth_type"] == "azure_ad" {
			azureFound = true
			if n.Properties["auth_flow"] == "client_certificate" {
				clientCertFlowFound = true
			}
			if n.Properties["tenant_id"] == "abc123-def456" {
				tenantFound = true
			}
		}
	}
	if !azureFound {
		t.Error("expected azure_ad guard")
	}
	if !clientCertFlowFound {
		t.Error("expected client_certificate auth_flow")
	}
	if !tenantFound {
		t.Error("expected extracted tenant_id")
	}
}

func TestCertificateTlsConfig(t *testing.T) {
	d := NewCertificateAuthDetector()
	src := `const tls = require('tls');
const server = tls.createServer({ cert: 'server.pem', key: 'server.key' });
`
	r := d.Detect(&detector.Context{FilePath: "server.ts", Language: "typescript", Content: src})
	if len(r.Nodes) < 1 {
		t.Error("expected >=1 tls_config guard")
	}
}

func TestCertificatePreScreenSkip(t *testing.T) {
	d := NewCertificateAuthDetector()
	r := d.Detect(&detector.Context{
		FilePath: "x.java", Language: "java",
		Content: "public class Foo {}",
	})
	if len(r.Nodes) != 0 {
		t.Error("expected pre-screen to short-circuit on text with no auth keywords")
	}
}

func TestCertificateNegative(t *testing.T) {
	d := NewCertificateAuthDetector()
	r := d.Detect(&detector.Context{FilePath: "x.java", Language: "java", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestCertificateDeterminism(t *testing.T) {
	d := NewCertificateAuthDetector()
	ctx := &detector.Context{FilePath: "nginx.conf", Language: "yaml", Content: "ssl_verify_client on;\n"}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
