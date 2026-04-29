package io.github.randomcodespace.iq.detector.auth;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;

@DetectorInfo(
    name = "certificate_auth",
    category = "auth",
    description = "Detects certificate-based authentication (mTLS, client certs, X.509)",
    languages = {"java", "python", "typescript", "csharp", "json", "yaml"},
    nodeKinds = {NodeKind.GUARD},
    properties = {"auth_type", "language"}
)
@Component
public class CertificateAuthDetector extends AbstractRegexDetector {
    private static final String PROP_AZURE_AD = "azure_ad";
    private static final String PROP_MTLS = "mtls";
    private static final String PROP_TLS_CONFIG = "tls_config";
    private static final String PROP_X509 = "x509";


    private record PatternDef(Pattern regex, String authType) {}

    private static final List<PatternDef> MTLS_PATTERNS = List.of(
            new PatternDef(Pattern.compile("\\bssl_verify_client\\b"), PROP_MTLS),
            new PatternDef(Pattern.compile("\\brequestCert\\s*:\\s*true\\b"), PROP_MTLS),
            new PatternDef(Pattern.compile("\\bclientAuth\\s*=\\s*\"true\""), PROP_MTLS),
            new PatternDef(Pattern.compile("\\bX509AuthenticationFilter\\b"), PROP_MTLS),
            new PatternDef(Pattern.compile("\\bAddCertificateForwarding\\b"), PROP_MTLS)
    );

    private static final List<PatternDef> X509_PATTERNS = List.of(
            new PatternDef(Pattern.compile("\\bX509AuthenticationFilter\\b"), PROP_X509),
            new PatternDef(Pattern.compile("\\bCertificateAuthenticationDefaults\\b"), PROP_X509),
            new PatternDef(Pattern.compile("\\.x509\\s*\\("), PROP_X509)
    );

    private static final List<PatternDef> TLS_CONFIG_PATTERNS = List.of(
            new PatternDef(Pattern.compile("\\bjavax\\.net\\.ssl\\.keyStore\\b"), PROP_TLS_CONFIG),
            new PatternDef(Pattern.compile("\\bssl\\.SSLContext\\b"), PROP_TLS_CONFIG),
            new PatternDef(Pattern.compile("\\btls\\.createServer\\b"), PROP_TLS_CONFIG),
            new PatternDef(Pattern.compile("(?:cert|key|ca)\\s*[=:]\\s*(?:fs\\.readFileSync\\s*\\(|['\"][\\w/.\\\\-]+\\.(?:pem|crt|key|cert)['\"])"), PROP_TLS_CONFIG),
            new PatternDef(Pattern.compile("\\btrustStore\\b"), PROP_TLS_CONFIG)
    );

    private static final List<PatternDef> AZURE_AD_PATTERNS = List.of(
            new PatternDef(Pattern.compile("\\bAzureAd\\b"), PROP_AZURE_AD),
            new PatternDef(Pattern.compile("\\bAZURE_TENANT_ID\\b"), PROP_AZURE_AD),
            new PatternDef(Pattern.compile("\\bAZURE_CLIENT_ID\\b"), PROP_AZURE_AD),
            new PatternDef(Pattern.compile("\\bmsal\\b"), PROP_AZURE_AD),
            new PatternDef(Pattern.compile("['\"]@azure/msal-browser['\"]"), PROP_AZURE_AD),
            new PatternDef(Pattern.compile("\\bAddMicrosoftIdentityWebApi\\b"), PROP_AZURE_AD),
            new PatternDef(Pattern.compile("\\bClientCertificateCredential\\b"), PROP_AZURE_AD)
    );

    private static final List<PatternDef> ALL_PATTERNS;

    static {
        ALL_PATTERNS = new ArrayList<>();
        ALL_PATTERNS.addAll(MTLS_PATTERNS);
        ALL_PATTERNS.addAll(X509_PATTERNS);
        ALL_PATTERNS.addAll(TLS_CONFIG_PATTERNS);
        ALL_PATTERNS.addAll(AZURE_AD_PATTERNS);
    }

    private static final Pattern CERT_PATH_RE = Pattern.compile("['\"]([^'\"]*\\.(?:pem|crt|key|cert|pfx|p12))['\"]");
    private static final Pattern TENANT_ID_RE = Pattern.compile("AZURE_TENANT_ID\\s*[=:]\\s*['\"]?([a-f0-9-]+)['\"]?");

    // Quick-reject pre-screen: a single regex pass over file content. If no
    // distinctive literal substring from any pattern in ALL_PATTERNS is
    // present, the file cannot match — short-circuit before the lines × patterns
    // double loop. Profiling on polyglot-bench (29.7K files, 14 languages) showed
    // this detector accounting for ~27% of detector CPU because it scanned every
    // YAML/JSON in supported-languages even when no auth keyword was present.
    private static final Pattern PRE_SCREEN = Pattern.compile(
            "ssl_verify_client|requestCert|clientAuth|X509|"
                    + "AddCertificateForwarding|CertificateAuthenticationDefaults|"
                    + "\\.x509\\(|javax\\.net\\.ssl|SSLContext|tls\\.createServer|"
                    + "trustStore|AzureAd|AZURE_TENANT_ID|AZURE_CLIENT_ID|"
                    + "ClientCertificateCredential|AddMicrosoftIdentityWebApi|"
                    + "msal|MSAL|@azure/msal|\\.pem|\\.crt|\\.cert");

    @Override
    public String getName() {
        return "certificate_auth";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java", "python", "typescript", "csharp", "json", "yaml");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }
        if (!PRE_SCREEN.matcher(text).find()) {
            return DetectorResult.empty();
        }

        String filePath = ctx.filePath();
        String[] lines = text.split("\n", -1);
        Set<Integer> seenLines = new HashSet<>();

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            for (PatternDef pdef : ALL_PATTERNS) {
                if (seenLines.contains(lineIdx)) {
                    break;
                }
                if (pdef.regex.matcher(line).find()) {
                    seenLines.add(lineIdx);
                    int lineNum = lineIdx + 1;
                    String matchedText = line.strip();

                    CodeNode node = new CodeNode();
                    node.setId("auth:" + filePath + ":cert:" + lineNum);
                    node.setKind(NodeKind.GUARD);
                    String truncLabel = matchedText.length() > 60 ? matchedText.substring(0, 60) : matchedText;
                    node.setLabel("Certificate auth (" + pdef.authType + "): " + truncLabel);
                    node.setFilePath(filePath);
                    node.setLineStart(lineNum);
                    node.setLineEnd(lineNum);
                    node.getProperties().put("auth_type", pdef.authType);
                    node.getProperties().put("language", ctx.language());
                    String truncPattern = matchedText.length() > 120 ? matchedText.substring(0, 120) : matchedText;
                    node.getProperties().put("pattern", truncPattern);

                    Matcher certM = CERT_PATH_RE.matcher(line);
                    if (certM.find()) {
                        node.getProperties().put("cert_path", certM.group(1));
                    }

                    Matcher tenantM = TENANT_ID_RE.matcher(line);
                    if (tenantM.find()) {
                        node.getProperties().put("tenant_id", tenantM.group(1));
                    }

                    if ("azure_ad".equals(pdef.authType)) {
                        if (line.contains("ClientCertificateCredential")) {
                            node.getProperties().put("auth_flow", "client_certificate");
                        } else if (line.toLowerCase().contains("msal")) {
                            node.getProperties().put("auth_flow", "msal");
                        }
                    }

                    nodes.add(node);
                }
            }
        }

        return DetectorResult.of(nodes, List.of());
    }
}
