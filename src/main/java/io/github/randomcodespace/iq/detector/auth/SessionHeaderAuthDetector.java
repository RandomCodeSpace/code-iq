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
    name = "session_header_auth",
    category = "auth",
    description = "Detects session and header-based authentication (cookies, API keys, tokens)",
    languages = {"java", "python", "typescript"},
    nodeKinds = {NodeKind.GUARD, NodeKind.MIDDLEWARE},
    properties = {"auth_type", "language"}
)
@Component
public class SessionHeaderAuthDetector extends AbstractRegexDetector {
    private static final String PROP_API_KEY = "api_key";
    private static final String PROP_CSRF = "csrf";
    private static final String PROP_HEADER = "header";
    private static final String PROP_SESSION = "session";


    private record PatternDef(Pattern regex, String authType, NodeKind nodeKind) {}

    private static final List<PatternDef> SESSION_PATTERNS = List.of(
            new PatternDef(Pattern.compile("['\"]express-session['\"]"), PROP_SESSION, NodeKind.MIDDLEWARE),
            new PatternDef(Pattern.compile("['\"]cookie-session['\"]"), PROP_SESSION, NodeKind.MIDDLEWARE),
            new PatternDef(Pattern.compile("@SessionAttributes\\b"), PROP_SESSION, NodeKind.GUARD),
            new PatternDef(Pattern.compile("\\bSessionMiddleware\\b"), PROP_SESSION, NodeKind.MIDDLEWARE),
            new PatternDef(Pattern.compile("\\bHttpSession\\b"), PROP_SESSION, NodeKind.GUARD),
            new PatternDef(Pattern.compile("\\bSESSION_ENGINE\\b"), PROP_SESSION, NodeKind.GUARD)
    );

    private static final List<PatternDef> HEADER_PATTERNS = List.of(
            new PatternDef(Pattern.compile("['\"]X-API-Key['\"]", Pattern.CASE_INSENSITIVE), PROP_HEADER, NodeKind.GUARD),
            new PatternDef(Pattern.compile("(?:req|request|ctx)\\.headers?\\s*\\[\\s*['\"]authorization['\"]\\s*]", Pattern.CASE_INSENSITIVE), PROP_HEADER, NodeKind.GUARD),
            new PatternDef(Pattern.compile("getHeader\\s*\\(\\s*['\"]Authorization['\"]", Pattern.CASE_INSENSITIVE), PROP_HEADER, NodeKind.GUARD)
    );

    private static final List<PatternDef> API_KEY_PATTERNS = List.of(
            new PatternDef(Pattern.compile("(?:req|request)\\.headers?\\s*\\[\\s*['\"]x-api-key['\"]\\s*]", Pattern.CASE_INSENSITIVE), PROP_API_KEY, NodeKind.GUARD),
            new PatternDef(Pattern.compile("\\bapi[_-]?key\\s*[=:]\\s*", Pattern.CASE_INSENSITIVE), PROP_API_KEY, NodeKind.GUARD),
            new PatternDef(Pattern.compile("\\bvalidate_?api_?key\\b", Pattern.CASE_INSENSITIVE), PROP_API_KEY, NodeKind.GUARD)
    );

    private static final List<PatternDef> CSRF_PATTERNS = List.of(
            new PatternDef(Pattern.compile("@csrf_protect\\b"), PROP_CSRF, NodeKind.GUARD),
            new PatternDef(Pattern.compile("\\bcsrf_exempt\\b"), PROP_CSRF, NodeKind.GUARD),
            new PatternDef(Pattern.compile("\\bCsrfViewMiddleware\\b"), PROP_CSRF, NodeKind.MIDDLEWARE),
            new PatternDef(Pattern.compile("['\"]csurf['\"]"), PROP_CSRF, NodeKind.MIDDLEWARE)
    );

    private static final List<PatternDef> ALL_PATTERNS;

    static {
        ALL_PATTERNS = new ArrayList<>();
        ALL_PATTERNS.addAll(SESSION_PATTERNS);
        ALL_PATTERNS.addAll(HEADER_PATTERNS);
        ALL_PATTERNS.addAll(API_KEY_PATTERNS);
        ALL_PATTERNS.addAll(CSRF_PATTERNS);
    }

    private static final Map<String, String> ID_TAG = Map.of(
            PROP_SESSION, PROP_SESSION,
            PROP_HEADER, PROP_HEADER,
            PROP_API_KEY, "apikey",
            PROP_CSRF, PROP_CSRF
    );

    // Quick-reject pre-screen — see CertificateAuthDetector for rationale.
    // Single regex pass over file content; if no distinctive substring of any
    // pattern in ALL_PATTERNS is present, the file cannot match — short-circuit
    // before the lines × patterns double loop. Profiling on polyglot-bench
    // showed this detector at ~23% of detector CPU; most TS/Python files have
    // no auth keyword at all.
    private static final Pattern PRE_SCREEN = Pattern.compile(
            "express-session|cookie-session|@SessionAttributes|SessionMiddleware|"
                    + "HttpSession|SESSION_ENGINE|"
                    + "(?i:X-API|Authorization|api[_-]?key|csurf|csrf|getHeader)");

    @Override
    public String getName() {
        return "session_header_auth";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java", "python", "typescript");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        if (!getSupportedLanguages().contains(ctx.language())) {
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
            for (PatternDef pdef : ALL_PATTERNS) {
                if (seenLines.contains(lineIdx)) {
                    break;
                }
                if (pdef.regex.matcher(line).find()) {
                    seenLines.add(lineIdx);
                    int lineNum = lineIdx + 1;
                    String matchedText = line.strip();
                    String tag = ID_TAG.get(pdef.authType);

                    CodeNode node = new CodeNode();
                    node.setId("auth:" + ctx.filePath() + ":" + tag + ":" + lineNum);
                    node.setKind(pdef.nodeKind);
                    String truncLabel = matchedText.length() > 70 ? matchedText.substring(0, 70) : matchedText;
                    node.setLabel(pdef.authType + " auth: " + truncLabel);
                    node.setFilePath(ctx.filePath());
                    node.setLineStart(lineNum);
                    node.setLineEnd(lineNum);
                    node.getProperties().put("auth_type", pdef.authType);
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
