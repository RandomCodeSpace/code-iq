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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;

@DetectorInfo(
    name = "ldap_auth",
    category = "auth",
    description = "Detects LDAP/Active Directory authentication configuration",
    languages = {"java", "python", "typescript", "csharp"},
    nodeKinds = {NodeKind.GUARD},
    properties = {"auth_type", "language"}
)
@Component
public class LdapAuthDetector extends AbstractRegexDetector {

    private static final List<Pattern> JAVA_PATTERNS = List.of(
            Pattern.compile("\\bLdapContextSource\\b"),
            Pattern.compile("\\bLdapTemplate\\b"),
            Pattern.compile("\\bActiveDirectoryLdapAuthenticationProvider\\b"),
            Pattern.compile("@EnableLdapRepositories\\b")
    );

    private static final List<Pattern> PYTHON_PATTERNS = List.of(
            Pattern.compile("\\bldap3\\.Connection\\b"),
            Pattern.compile("\\bldap3\\.Server\\b"),
            Pattern.compile("\\bAUTH_LDAP_SERVER_URI\\b"),
            Pattern.compile("\\bAUTH_LDAP_BIND_DN\\b")
    );

    private static final List<Pattern> TS_PATTERNS = List.of(
            Pattern.compile("require\\s*\\(\\s*['\"]ldapjs['\"]\\s*\\)"),
            Pattern.compile("(?:import\\s+.*\\s+from\\s+['\"]ldapjs['\"]|import\\s+ldapjs\\b)"),
            Pattern.compile("['\"]passport-ldapauth['\"]")
    );

    private static final List<Pattern> CSHARP_PATTERNS = List.of(
            Pattern.compile("\\bSystem\\.DirectoryServices\\b"),
            Pattern.compile("\\bLdapConnection\\b"),
            Pattern.compile("\\bDirectoryEntry\\b")
    );

    private static final Map<String, List<Pattern>> LANGUAGE_PATTERNS = Map.of(
            "java", JAVA_PATTERNS,
            "python", PYTHON_PATTERNS,
            "typescript", TS_PATTERNS,
            "csharp", CSHARP_PATTERNS
    );

    // Quick-reject pre-screen — see CertificateAuthDetector for rationale.
    // Most code files don't mention LDAP at all; one regex pass over content
    // skips the lines × patterns double loop in those cases.
    private static final Pattern PRE_SCREEN = Pattern.compile(
            "(?i:ldap)|DirectoryServices|DirectoryEntry");

    @Override
    public String getName() {
        return "ldap_auth";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java", "python", "typescript", "csharp");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        List<Pattern> patterns = LANGUAGE_PATTERNS.get(ctx.language());
        if (patterns == null) {
            return DetectorResult.empty();
        }

        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }
        if (!PRE_SCREEN.matcher(text).find()) {
            return DetectorResult.empty();
        }

        List<CodeNode> nodes = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        Set<Integer> seenLines = new HashSet<>();

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            for (Pattern pattern : patterns) {
                if (pattern.matcher(line).find() && !seenLines.contains(lineIdx)) {
                    seenLines.add(lineIdx);
                    int lineNum = lineIdx + 1;
                    String matchedText = line.strip();

                    CodeNode node = new CodeNode();
                    node.setId("auth:" + ctx.filePath() + ":ldap:" + lineNum);
                    node.setKind(NodeKind.GUARD);
                    String truncLabel = matchedText.length() > 80 ? matchedText.substring(0, 80) : matchedText;
                    node.setLabel("LDAP auth: " + truncLabel);
                    node.setFilePath(ctx.filePath());
                    node.setLineStart(lineNum);
                    node.setLineEnd(lineNum);
                    node.getProperties().put("auth_type", "ldap");
                    node.getProperties().put("language", ctx.language());
                    String truncPattern = matchedText.length() > 120 ? matchedText.substring(0, 120) : matchedText;
                    node.getProperties().put("pattern", truncPattern);
                    nodes.add(node);
                }
            }
        }

        return DetectorResult.of(nodes, List.of());
    }
}
