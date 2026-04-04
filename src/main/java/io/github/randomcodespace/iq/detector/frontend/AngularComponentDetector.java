package io.github.randomcodespace.iq.detector.frontend;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;

@DetectorInfo(
    name = "frontend.angular_components",
    category = "frontend",
    description = "Detects Angular components (@Component decorator, selectors)",
    languages = {"typescript"},
    nodeKinds = {NodeKind.COMPONENT, NodeKind.MIDDLEWARE},
    properties = {"framework", "selector"}
)
@Component
public class AngularComponentDetector extends AbstractRegexDetector {
    private static final String PROP_ANGULAR = "angular";
    private static final String PROP_COMPONENT = "component";
    private static final String PROP_DECORATOR = "decorator";


    private static final Pattern COMPONENT_DECORATOR = Pattern.compile(
            "@Component\\s*\\(\\s*\\{.*?selector\\s*:\\s*['\"]([^'\"]+)['\"].*?\\}\\s*\\)\\s*\\n?\\s*(?:export\\s+)?class\\s+(\\w+)",
            Pattern.DOTALL
    );
    private static final Pattern INJECTABLE_DECORATOR = Pattern.compile(
            "@Injectable\\s*\\(\\s*\\{.*?providedIn\\s*:\\s*['\"]([\\w]+)['\"].*?\\}\\s*\\)\\s*\\n?\\s*(?:export\\s+)?class\\s+(\\w+)",
            Pattern.DOTALL
    );
    private static final Pattern DIRECTIVE_DECORATOR = Pattern.compile(
            "@Directive\\s*\\(\\s*\\{.*?selector\\s*:\\s*['\"]([^'\"]+)['\"].*?\\}\\s*\\)\\s*\\n?\\s*(?:export\\s+)?class\\s+(\\w+)",
            Pattern.DOTALL
    );
    private static final Pattern PIPE_DECORATOR = Pattern.compile(
            "@Pipe\\s*\\(\\s*\\{.*?name\\s*:\\s*['\"]([\\w]+)['\"].*?\\}\\s*\\)\\s*\\n?\\s*(?:export\\s+)?class\\s+(\\w+)",
            Pattern.DOTALL
    );
    private static final Pattern NGMODULE_DECORATOR = Pattern.compile(
            "@NgModule\\s*\\(\\s*\\{.*?\\}\\s*\\)\\s*\\n?\\s*(?:export\\s+)?class\\s+(\\w+)",
            Pattern.DOTALL
    );

    @Override
    public String getName() {
        return "frontend.angular_components";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }

        List<CodeNode> nodes = new ArrayList<>();
        String filePath = ctx.filePath();
        Set<String> seen = new HashSet<>();

        // @Component
        Matcher m = COMPONENT_DECORATOR.matcher(text);
        while (m.find()) {
            String selector = m.group(1);
            String className = m.group(2);
            if (!seen.add(className)) continue;
            CodeNode node = FrontendDetectorHelper.createComponentNode(PROP_ANGULAR, filePath, PROP_COMPONENT,
                    className, NodeKind.COMPONENT, FrontendDetectorHelper.lineAt(text, m.start()));
            node.getProperties().put("selector", selector);
            node.getProperties().put(PROP_DECORATOR, "Component");
            nodes.add(node);
        }

        // @Injectable
        m = INJECTABLE_DECORATOR.matcher(text);
        while (m.find()) {
            String providedIn = m.group(1);
            String className = m.group(2);
            if (!seen.add(className)) continue;
            CodeNode node = FrontendDetectorHelper.createComponentNode(PROP_ANGULAR, filePath, "service",
                    className, NodeKind.MIDDLEWARE, FrontendDetectorHelper.lineAt(text, m.start()));
            node.getProperties().put("provided_in", providedIn);
            node.getProperties().put(PROP_DECORATOR, "Injectable");
            nodes.add(node);
        }

        // @Directive
        m = DIRECTIVE_DECORATOR.matcher(text);
        while (m.find()) {
            String selector = m.group(1);
            String className = m.group(2);
            if (!seen.add(className)) continue;
            CodeNode node = FrontendDetectorHelper.createComponentNode(PROP_ANGULAR, filePath, PROP_COMPONENT,
                    className, NodeKind.COMPONENT, FrontendDetectorHelper.lineAt(text, m.start()));
            node.getProperties().put("selector", selector);
            node.getProperties().put(PROP_DECORATOR, "Directive");
            nodes.add(node);
        }

        // @Pipe
        m = PIPE_DECORATOR.matcher(text);
        while (m.find()) {
            String pipeName = m.group(1);
            String className = m.group(2);
            if (!seen.add(className)) continue;
            CodeNode node = FrontendDetectorHelper.createComponentNode(PROP_ANGULAR, filePath, PROP_COMPONENT,
                    className, NodeKind.COMPONENT, FrontendDetectorHelper.lineAt(text, m.start()));
            node.getProperties().put("pipe_name", pipeName);
            node.getProperties().put(PROP_DECORATOR, "Pipe");
            nodes.add(node);
        }

        // @NgModule
        m = NGMODULE_DECORATOR.matcher(text);
        while (m.find()) {
            String className = m.group(1);
            if (!seen.add(className)) continue;
            CodeNode node = FrontendDetectorHelper.createComponentNode(PROP_ANGULAR, filePath, PROP_COMPONENT,
                    className, NodeKind.COMPONENT, FrontendDetectorHelper.lineAt(text, m.start()));
            node.getProperties().put(PROP_DECORATOR, "NgModule");
            nodes.add(node);
        }

        return DetectorResult.of(nodes, List.of());
    }
}
