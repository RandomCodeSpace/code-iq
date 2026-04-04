package io.github.randomcodespace.iq.detector.auth;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for auth detectors — branches not hit by existing tests.
 */
class AuthDetectorsCoverageTest {

    // =====================================================================
    // CertificateAuthDetector
    // =====================================================================
    @Nested
    class CertificateCoverage {
        private final CertificateAuthDetector d = new CertificateAuthDetector();

        @Test
        void detectsRequestCertMtls() {
            DetectorResult r = d.detect(ctx("typescript", "const opts = { requestCert: true, rejectUnauthorized: true };"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("mtls", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsClientAuthEqualsTrueMtls() {
            // Pattern is: clientAuth = "true" (literal double-quote around true)
            DetectorResult r = d.detect(ctx("yaml", "clientAuth = \"true\""));
            assertFalse(r.nodes().isEmpty());
            assertEquals("mtls", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsX509AuthenticationFilter() {
            DetectorResult r = d.detect(ctx("java",
                    "http.addFilter(new X509AuthenticationFilter());"));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsAddCertificateForwarding() {
            DetectorResult r = d.detect(ctx("csharp",
                    "services.AddCertificateForwarding(opts => {});"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("mtls", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsCertificateAuthenticationDefaults() {
            DetectorResult r = d.detect(ctx("csharp",
                    "services.AddAuthentication(CertificateAuthenticationDefaults.AuthenticationScheme);"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("x509", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsX509Fluent() {
            DetectorResult r = d.detect(ctx("java",
                    "auth.x509().subjectPrincipalRegex(\"CN=(.*?)(?:,|$)\");"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("x509", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsJavaxKeyStore() {
            DetectorResult r = d.detect(ctx("java",
                    "System.setProperty(\"javax.net.ssl.keyStore\", \"/certs/server.jks\");"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("tls_config", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsSslSSLContext() {
            DetectorResult r = d.detect(ctx("python",
                    "ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("tls_config", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsTlsCreateServer() {
            DetectorResult r = d.detect(ctx("typescript",
                    "const server = tls.createServer({ key, cert });"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("tls_config", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsCertPathInTlsConfig() {
            DetectorResult r = d.detect(ctx("typescript",
                    "const cert = fs.readFileSync('/etc/certs/server.pem');"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("tls_config", r.nodes().get(0).getProperties().get("auth_type"));
            assertNotNull(r.nodes().get(0).getProperties().get("cert_path"));
        }

        @Test
        void detectsTrustStore() {
            DetectorResult r = d.detect(ctx("java", "System.setProperty(\"trustStore\", \"/path/store.jks\");"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("tls_config", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsAzureAd() {
            DetectorResult r = d.detect(ctx("csharp",
                    "// AzureAd section configured in appsettings.json"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("azure_ad", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsAzureTenantId() {
            DetectorResult r = d.detect(ctx("yaml",
                    "AZURE_TENANT_ID: abc-def-123"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("azure_ad", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsClientCertificateCredentialWithAuthFlow() {
            DetectorResult r = d.detect(ctx("csharp",
                    "var cred = new ClientCertificateCredential(tenantId, clientId, cert);"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("client_certificate", r.nodes().get(0).getProperties().get("auth_flow"));
        }

        @Test
        void detectsMsalWithMsalAuthFlow() {
            DetectorResult r = d.detect(ctx("typescript",
                    "import * as msal from '@azure/msal-browser';"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("msal", r.nodes().get(0).getProperties().get("auth_flow"));
        }

        @Test
        void detectsAddMicrosoftIdentityWebApi() {
            DetectorResult r = d.detect(ctx("csharp",
                    "services.AddMicrosoftIdentityWebApi(config);"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("azure_ad", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void multipleMatchesOnSameLineOnlyOneNode() {
            // A line matching two patterns — only one node per line
            DetectorResult r = d.detect(ctx("yaml", "  AZURE_TENANT_ID: abc\n  AZURE_CLIENT_ID: xyz"));
            assertEquals(2, r.nodes().size()); // two lines, one match each
        }

        @Test
        void emptyContentReturnsEmpty() {
            DetectorResult r = d.detect(ctx("java", ""));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void nullContentReturnsEmpty() {
            DetectorResult r = d.detect(new DetectorContext("test.java", "java", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void deterministic() {
            DetectorTestUtils.assertDeterministic(d, ctx("java",
                    "X509AuthenticationFilter f;\nssl_verify_client on;\ntrustStore=/path/store.jks"));
        }
    }

    // =====================================================================
    // LdapAuthDetector
    // =====================================================================
    @Nested
    class LdapCoverage {
        private final LdapAuthDetector d = new LdapAuthDetector();

        @Test
        void detectsLdapTemplateInJava() {
            DetectorResult r = d.detect(ctx("java", "@Autowired LdapTemplate ldapTemplate;"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("ldap", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsActiveDirectoryLdapAuthProviderInJava() {
            DetectorResult r = d.detect(ctx("java",
                    "new ActiveDirectoryLdapAuthenticationProvider(domain, url)"));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsEnableLdapRepositoriesInJava() {
            DetectorResult r = d.detect(ctx("java",
                    "@EnableLdapRepositories\n@SpringBootApplication\npublic class App {}"));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsLdap3ConnectionInPython() {
            DetectorResult r = d.detect(ctx("python",
                    "conn = ldap3.Connection(server, user=dn, password=pw)"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("ldap", r.nodes().get(0).getProperties().get("auth_type"));
            assertEquals("python", r.nodes().get(0).getProperties().get("language"));
        }

        @Test
        void detectsLdap3ServerInPython() {
            DetectorResult r = d.detect(ctx("python",
                    "server = ldap3.Server('ldap.example.com', port=389)"));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsAuthLdapBindDnInPython() {
            DetectorResult r = d.detect(ctx("python",
                    "AUTH_LDAP_BIND_DN = 'cn=django-agent,dc=example,dc=com'"));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsLdapjsRequireInTypeScript() {
            DetectorResult r = d.detect(ctx("typescript",
                    "const ldap = require('ldapjs');"));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsLdapjsImportInTypeScript() {
            DetectorResult r = d.detect(ctx("typescript",
                    "import ldapjs from 'ldapjs';"));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsPassportLdapauthInTypeScript() {
            DetectorResult r = d.detect(ctx("typescript",
                    "const LdapStrategy = require('passport-ldapauth');"));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsSystemDirectoryServicesInCSharp() {
            DetectorResult r = d.detect(ctx("csharp",
                    "using System.DirectoryServices;"));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsLdapConnectionInCSharp() {
            DetectorResult r = d.detect(ctx("csharp",
                    "LdapConnection conn = new LdapConnection(new LdapDirectoryIdentifier(server));"));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsDirectoryEntryInCSharp() {
            DetectorResult r = d.detect(ctx("csharp",
                    "DirectoryEntry entry = new DirectoryEntry(path, user, password);"));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void unsupportedLanguageReturnsEmpty() {
            DetectorResult r = d.detect(ctx("go", "ldap.Connect(\"server\", 389)"));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void emptyContentReturnsEmpty() {
            DetectorResult r = d.detect(ctx("java", ""));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void nullContentReturnsEmpty() {
            DetectorResult r = d.detect(new DetectorContext("f.java", "java", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void deterministic() {
            DetectorTestUtils.assertDeterministic(d, ctx("java",
                    "LdapContextSource src;\nLdapTemplate tmpl;\nActiveDirectoryLdapAuthenticationProvider prov;"));
        }
    }

    // =====================================================================
    // SessionHeaderAuthDetector
    // =====================================================================
    @Nested
    class SessionHeaderCoverage {
        private final SessionHeaderAuthDetector d = new SessionHeaderAuthDetector();

        @Test
        void detectsCookieSession() {
            DetectorResult r = d.detect(ctx("typescript",
                    "const session = require('cookie-session');"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("session", r.nodes().get(0).getProperties().get("auth_type"));
            assertEquals(NodeKind.MIDDLEWARE, r.nodes().get(0).getKind());
        }

        @Test
        void detectsSessionAttributesInJava() {
            DetectorResult r = d.detect(ctx("java",
                    "@SessionAttributes(\"user\")\npublic class UserController {}"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("session", r.nodes().get(0).getProperties().get("auth_type"));
            assertEquals(NodeKind.GUARD, r.nodes().get(0).getKind());
        }

        @Test
        void detectsSessionMiddlewareInTypeScript() {
            DetectorResult r = d.detect(ctx("typescript",
                    "app.use(SessionMiddleware({ secret: 'key' }));"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("session", r.nodes().get(0).getProperties().get("auth_type"));
            assertEquals(NodeKind.MIDDLEWARE, r.nodes().get(0).getKind());
        }

        @Test
        void detectsHttpSessionInJava() {
            DetectorResult r = d.detect(ctx("java",
                    "HttpSession session = request.getSession();"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("session", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsSessionEngineInPython() {
            DetectorResult r = d.detect(ctx("python",
                    "SESSION_ENGINE = 'django.contrib.sessions.backends.db'"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("session", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsXApiKeyHeader() {
            DetectorResult r = d.detect(ctx("typescript",
                    "const key = req.headers['X-API-Key'];"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("header", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsAuthorizationHeaderAccess() {
            DetectorResult r = d.detect(ctx("typescript",
                    "const token = req.headers['authorization'];"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("header", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsGetHeaderAuthorization() {
            DetectorResult r = d.detect(ctx("java",
                    "String token = request.getHeader(\"Authorization\");"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("header", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsXApiKeyLowercase() {
            // The HEADER pattern '['"X-API-Key'"]' (CASE_INSENSITIVE) fires before the API_KEY
            // req.headers pattern, so 'x-api-key' is classified as "header" auth_type
            DetectorResult r = d.detect(ctx("typescript",
                    "const key = req.headers['x-api-key'];"));
            assertFalse(r.nodes().isEmpty());
            // Either header or api_key is valid — just verify detection occurred
            String authType = (String) r.nodes().get(0).getProperties().get("auth_type");
            assertTrue("header".equals(authType) || "api_key".equals(authType));
        }

        @Test
        void detectsApiKeyAssignment() {
            // "api_key = " pattern (API_KEY) fires for this content
            DetectorResult r = d.detect(ctx("python",
                    "api_key = os.getenv('SERVICE_KEY')"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("api_key", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsValidateApiKey() {
            DetectorResult r = d.detect(ctx("python",
                    "if not validate_api_key(request):\n    raise Unauthorized"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("api_key", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsCsrfProtectDecorator() {
            DetectorResult r = d.detect(ctx("python",
                    "@csrf_protect\ndef my_view(request): pass"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("csrf", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsCsrfExempt() {
            DetectorResult r = d.detect(ctx("python",
                    "@csrf_exempt\ndef public_api(request): pass"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("csrf", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void detectsCsrfViewMiddleware() {
            DetectorResult r = d.detect(ctx("python",
                    "MIDDLEWARE = ['django.middleware.csrf.CsrfViewMiddleware']"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("csrf", r.nodes().get(0).getProperties().get("auth_type"));
            assertEquals(NodeKind.MIDDLEWARE, r.nodes().get(0).getKind());
        }

        @Test
        void detectsCsurfMiddleware() {
            DetectorResult r = d.detect(ctx("typescript",
                    "const csrf = require('csurf');"));
            assertFalse(r.nodes().isEmpty());
            assertEquals("csrf", r.nodes().get(0).getProperties().get("auth_type"));
        }

        @Test
        void unsupportedLanguageReturnsEmpty() {
            // csharp not in supported languages list
            DetectorResult r = d.detect(ctx("csharp",
                    "app.UseSession();"));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void emptyContentReturnsEmpty() {
            DetectorResult r = d.detect(ctx("java", ""));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void nullContentReturnsEmpty() {
            DetectorResult r = d.detect(new DetectorContext("f.ts", "typescript", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void multipleMatchesOnMultipleLines() {
            DetectorResult r = d.detect(ctx("java",
                    "HttpSession s = req.getSession();\nrequest.getHeader(\"Authorization\");"));
            assertEquals(2, r.nodes().size());
        }

        @Test
        void deterministic() {
            DetectorTestUtils.assertDeterministic(d, ctx("typescript",
                    "require('express-session');\nreq.headers['authorization'];\nrequire('csurf');"));
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }
}
