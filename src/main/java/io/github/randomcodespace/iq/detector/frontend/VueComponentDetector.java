package io.github.randomcodespace.iq.detector.frontend;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;

@DetectorInfo(
    name = "frontend.vue_components",
    category = "frontend",
    description = "Detects Vue.js components (Options API, Composition API, SFC)",
    languages = {"typescript", "javascript", "vue"},
    nodeKinds = {NodeKind.COMPONENT, NodeKind.HOOK},
    properties = {"framework"}
)
@Component
public class VueComponentDetector extends AbstractRegexDetector {

    private static final Pattern DEFINE_COMPONENT_NAME = Pattern.compile(
            "export\\s+default\\s+defineComponent\\s*\\(\\s*\\{[^}]*?name\\s*:\\s*['\"]([\\w]+)['\"]",
            Pattern.DOTALL
    );
    private static final Pattern OPTIONS_API_NAME = Pattern.compile(
            "export\\s+default\\s+\\{\\s*name\\s*:\\s*['\"]([\\w]+)['\"]"
    );
    private static final Pattern SCRIPT_SETUP = Pattern.compile(
            "<script\\s+setup(?:\\s+lang\\s*=\\s*['\"](?:ts|js)['\"])?\\s*>"
    );
    private static final Pattern EXPORT_FUNC_COMPOSABLE = Pattern.compile(
            "export\\s+function\\s+(use[A-Z]\\w*)\\s*\\("
    );
    private static final Pattern EXPORT_CONST_COMPOSABLE = Pattern.compile(
            "export\\s+const\\s+(use[A-Z]\\w*)\\s*=\\s*"
    );

    @Override
    public String getName() {
        return "frontend.vue_components";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript", "javascript", "vue");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }

        List<CodeNode> nodes = new ArrayList<>();
        String filePath = ctx.filePath();
        List<String> componentNames = new ArrayList<>();

        // defineComponent with name
        Matcher m = DEFINE_COMPONENT_NAME.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (componentNames.contains(name)) continue;
            CodeNode node = FrontendDetectorHelper.createComponentNode("vue", filePath, "component",
                    name, NodeKind.COMPONENT, FrontendDetectorHelper.lineAt(text, m.start()));
            node.getProperties().put("api_style", "composition");
            nodes.add(node);
            componentNames.add(name);
        }

        // Options API
        m = OPTIONS_API_NAME.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (componentNames.contains(name)) continue;
            CodeNode node = FrontendDetectorHelper.createComponentNode("vue", filePath, "component",
                    name, NodeKind.COMPONENT, FrontendDetectorHelper.lineAt(text, m.start()));
            node.getProperties().put("api_style", "options");
            nodes.add(node);
            componentNames.add(name);
        }

        // <script setup>
        m = SCRIPT_SETUP.matcher(text);
        while (m.find()) {
            String compName = extractScriptSetupName(filePath);
            if (compName == null || componentNames.contains(compName)) continue;
            CodeNode node = FrontendDetectorHelper.createComponentNode("vue", filePath, "component",
                    compName, NodeKind.COMPONENT, FrontendDetectorHelper.lineAt(text, m.start()));
            node.getProperties().put("api_style", "script_setup");
            nodes.add(node);
            componentNames.add(compName);
        }

        // Composables (hooks)
        List<String> hookNames = new ArrayList<>();
        for (Pattern pattern : List.of(EXPORT_FUNC_COMPOSABLE, EXPORT_CONST_COMPOSABLE)) {
            Matcher hm = pattern.matcher(text);
            while (hm.find()) {
                String name = hm.group(1);
                if (hookNames.contains(name)) continue;
                nodes.add(FrontendDetectorHelper.createComponentNode("vue", filePath, "hook",
                        name, NodeKind.HOOK, FrontendDetectorHelper.lineAt(text, hm.start())));
                hookNames.add(name);
            }
        }

        return DetectorResult.of(nodes, List.of());
    }

    private static String extractScriptSetupName(String filePath) {
        String normalized = filePath.replace("\\", "/");
        int lastSlash = normalized.lastIndexOf('/');
        String filename = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        if (filename.endsWith(".vue")) {
            return filename.substring(0, filename.length() - 4);
        }
        return null;
    }
}
