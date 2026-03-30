package io.github.randomcodespace.iq.detector.kotlin;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

// Kotlin Compiler API (kotlin-compiler-embeddable) evaluated 2026-03-28 but regex/ANTLR
// approach provides sufficient detection quality for Ktor route detection. The compiler JAR
// adds 50-70MB for only 2 Kotlin detectors — not justified. Revisit if complex semantic
// analysis (type resolution, coroutine flow tracking) is needed in the future.
@DetectorInfo(
    name = "ktor_routes",
    category = "endpoints",
    description = "Detects Ktor route definitions and authentication blocks",
    parser = ParserType.REGEX,
    languages = {"kotlin"},
    nodeKinds = {NodeKind.ENDPOINT, NodeKind.GUARD, NodeKind.MIDDLEWARE, NodeKind.MODULE},
    properties = {"framework", "http_method", "protocol"}
)
@Component
public class KtorRouteDetector extends AbstractAntlrDetector {

    private static final Pattern ENDPOINT_PATTERN = Pattern.compile("\\b(get|post|put|delete|patch)\\(\\s*\"([^\"]+)\"\\s*\\)\\s*\\{");
    private static final Pattern ROUTING_PATTERN = Pattern.compile("\\brouting\\s*\\{");
    private static final Pattern ROUTE_PREFIX_PATTERN = Pattern.compile("\\broute\\(\\s*\"([^\"]+)\"\\s*\\)\\s*\\{");
    private static final Pattern INSTALL_PATTERN = Pattern.compile("\\binstall\\(\\s*(\\w+)\\s*\\)");
    private static final Pattern AUTHENTICATE_PATTERN = Pattern.compile("\\bauthenticate\\(\\s*\"([^\"]+)\"\\s*\\)\\s*\\{");

    @Override
    public String getName() { return "ktor_routes"; }

    @Override
    public Set<String> getSupportedLanguages() { return Set.of("kotlin"); }

    private Map<Integer, String> buildPrefixMap(String text) {
        Map<Integer, String> prefixes = new HashMap<>();
        List<int[]> activePrefixes = new ArrayList<>(); // [prefixIdx, braceDepth]
        List<String> prefixValues = new ArrayList<>();
        int braceDepth = 0;
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            braceDepth += countChar(line, '{') - countChar(line, '}');

            Matcher rm = ROUTE_PREFIX_PATTERN.matcher(line);
            if (rm.find()) {
                prefixValues.add(rm.group(1));
                activePrefixes.add(new int[]{prefixValues.size() - 1, braceDepth});
            }

            while (!activePrefixes.isEmpty() && braceDepth < activePrefixes.get(activePrefixes.size() - 1)[1]) {
                activePrefixes.remove(activePrefixes.size() - 1);
            }

            if (!activePrefixes.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int[] ap : activePrefixes) sb.append(prefixValues.get(ap[0]));
                prefixes.put(i + 1, sb.toString());
            }
        }
        return prefixes;
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) count++;
        return count;
    }
    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // Skip ANTLR parsing — regex is the primary detection method for this detector
        // ANTLR infrastructure is in place for future enhancement
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        List<CodeNode> nodes = new ArrayList<>();
        String fp = ctx.filePath();
        Map<Integer, String> prefixMap = buildPrefixMap(text);

        Matcher m = ROUTING_PATTERN.matcher(text);
        while (m.find()) {
            int line = text.substring(0, m.start()).split("\n", -1).length;
            CodeNode n = new CodeNode(); n.setId("ktor:" + fp + ":routing:" + line);
            n.setKind(NodeKind.MODULE); n.setLabel("routing"); n.setFqn(fp + "::routing");
            n.setFilePath(fp); n.setLineStart(line);
            n.getProperties().put("framework", "ktor"); n.getProperties().put("type", "router");
            nodes.add(n);
        }

        m = ENDPOINT_PATTERN.matcher(text);
        while (m.find()) {
            String method = m.group(1).toUpperCase();
            String rawPath = m.group(2);
            int line = text.substring(0, m.start()).split("\n", -1).length;
            String prefix = prefixMap.getOrDefault(line, "");
            String path = prefix + rawPath;
            CodeNode n = new CodeNode(); n.setId("ktor:" + fp + ":" + method + ":" + path + ":" + line);
            n.setKind(NodeKind.ENDPOINT); n.setLabel(method + " " + path);
            n.setFqn(fp + "::" + method + ":" + path); n.setFilePath(fp); n.setLineStart(line);
            n.getProperties().put("protocol", "REST"); n.getProperties().put("http_method", method);
            n.getProperties().put("path_pattern", path); n.getProperties().put("framework", "ktor");
            nodes.add(n);
        }

        m = INSTALL_PATTERN.matcher(text);
        while (m.find()) {
            String feature = m.group(1);
            int line = text.substring(0, m.start()).split("\n", -1).length;
            CodeNode n = new CodeNode(); n.setId("ktor:" + fp + ":install:" + feature + ":" + line);
            n.setKind(NodeKind.MIDDLEWARE); n.setLabel("install:" + feature);
            n.setFqn(fp + "::install:" + feature); n.setFilePath(fp); n.setLineStart(line);
            n.getProperties().put("framework", "ktor"); n.getProperties().put("feature", feature);
            nodes.add(n);
        }

        m = AUTHENTICATE_PATTERN.matcher(text);
        while (m.find()) {
            String authName = m.group(1);
            int line = text.substring(0, m.start()).split("\n", -1).length;
            CodeNode n = new CodeNode(); n.setId("ktor:" + fp + ":auth:" + authName + ":" + line);
            n.setKind(NodeKind.GUARD); n.setLabel("authenticate:" + authName);
            n.setFqn(fp + "::authenticate:" + authName); n.setFilePath(fp); n.setLineStart(line);
            n.getProperties().put("framework", "ktor"); n.getProperties().put("auth_name", authName);
            nodes.add(n);
        }

        return DetectorResult.of(nodes, List.of());
    }
}
