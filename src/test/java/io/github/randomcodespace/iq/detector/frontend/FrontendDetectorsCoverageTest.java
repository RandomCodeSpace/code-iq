package io.github.randomcodespace.iq.detector.frontend;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for frontend detectors — branches not hit by
 * existing tests.
 */
class FrontendDetectorsCoverageTest {

    // =====================================================================
    // ReactComponentDetector
    // =====================================================================
    @Nested
    class ReactCoverage {
        private final ReactComponentDetector d = new ReactComponentDetector();

        @Test
        void classExtendsReactComponentIsDetected() {
            String code = """
                    class Dashboard extends React.Component {
                        render() { return <div><Widget /></div>; }
                    }
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals(NodeKind.COMPONENT, r.nodes().get(0).getKind());
            assertEquals("class", r.nodes().get(0).getProperties().get("component_type"));
        }

        @Test
        void classExtendsComponentIsDetected() {
            String code = """
                    class Login extends Component {
                        render() { return <form/>; }
                    }
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("Login", r.nodes().get(0).getLabel());
        }

        @Test
        void multipleHooksExportedAsConst() {
            String code = """
                    export const useFetch = () => { return {}; };
                    export const useDebounce = () => { return {}; };
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(2, r.nodes().size());
            assertTrue(r.nodes().stream().allMatch(n -> n.getKind() == NodeKind.HOOK));
        }

        @Test
        void duplicateComponentNameIsDeduped() {
            String code = """
                    export default function App() { return <div/>; }
                    export default function App() { return <span/>; }
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            // Should only appear once (deduplicated)
            assertEquals(1, r.nodes().size());
        }

        @Test
        void duplicateHookIsDeduped() {
            String code = """
                    export function useData() { return {}; }
                    export function useData() { return {}; }
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
        }

        @Test
        void nullContentReturnsEmpty() {
            DetectorResult r = d.detect(new DetectorContext("App.tsx", "typescript", null));
            assertTrue(r.nodes().isEmpty());
            assertTrue(r.edges().isEmpty());
        }

        @Test
        void emptyContentReturnsEmpty() {
            DetectorResult r = d.detect(ctx("typescript", ""));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void exportConstFCPattern() {
            String code = "export const Nav: React.FC = () => <nav/>;";
            DetectorResult r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("Nav", r.nodes().get(0).getLabel());
            assertEquals("function", r.nodes().get(0).getProperties().get("component_type"));
        }

        @Test
        void jsxTagsFromMultipleComponentsAreScopedCorrectly() {
            // Each component should only get its own JSX children
            String code = """
                    export const Alpha = () => <Beta />;
                    export const Gamma = () => <Delta />;
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(2, r.nodes().size());

            List<String> alphaRenders = r.edges().stream()
                    .filter(e -> e.getKind() == EdgeKind.RENDERS && e.getSourceId().contains("Alpha"))
                    .map(e -> e.getTarget().getId())
                    .toList();
            List<String> gammaRenders = r.edges().stream()
                    .filter(e -> e.getKind() == EdgeKind.RENDERS && e.getSourceId().contains("Gamma"))
                    .map(e -> e.getTarget().getId())
                    .toList();
            assertTrue(alphaRenders.contains("Beta"));
            assertFalse(alphaRenders.contains("Delta"));
            assertTrue(gammaRenders.contains("Delta"));
            assertFalse(gammaRenders.contains("Beta"));
        }

        @Test
        void deterministic() {
            DetectorTestUtils.assertDeterministic(d, ctx("typescript",
                    """
                    class Modal extends React.Component {
                        render() { return <div><Button /><Icon /></div>; }
                    }
                    export function useModal() { return {}; }
                    """));
        }
    }

    // =====================================================================
    // VueComponentDetector
    // =====================================================================
    @Nested
    class VueCoverage {
        private final VueComponentDetector d = new VueComponentDetector();

        @Test
        void detectsOptionsApiStyle() {
            String code = "export default { name: 'ProductList' }";
            DetectorResult r = d.detect(ctx("javascript", code));
            assertEquals(1, r.nodes().size());
            assertEquals("options", r.nodes().get(0).getProperties().get("api_style"));
        }

        @Test
        void detectsDefineComponentCompositionStyle() {
            String code = """
                    export default defineComponent({
                      name: 'ShoppingCart',
                      setup() { return {}; }
                    })
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals("ShoppingCart", r.nodes().get(0).getLabel());
            assertEquals("composition", r.nodes().get(0).getProperties().get("api_style"));
        }

        @Test
        void detectsComposableFunctionWithRef() {
            String code = """
                    export function useTheme() {
                        const theme = ref('light');
                        return { theme };
                    }
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals(NodeKind.HOOK, r.nodes().get(0).getKind());
        }

        @Test
        void detectsComposableConstWithRef() {
            String code = """
                    export const useLocale = () => {
                        const locale = ref('en');
                        return { locale };
                    }
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals(NodeKind.HOOK, r.nodes().get(0).getKind());
            assertEquals("useLocale", r.nodes().get(0).getLabel());
        }

        @Test
        void scriptSetupExtractsNameFromVueFile() {
            String code = "<script setup lang=\"ts\">\nconst msg = 'hello'\n</script>\n<template><div>{{ msg }}</div></template>";
            DetectorResult r = d.detect(new DetectorContext("components/MyWidget.vue", "vue", code));
            assertEquals(1, r.nodes().size());
            assertEquals("MyWidget", r.nodes().get(0).getLabel());
            assertEquals("script_setup", r.nodes().get(0).getProperties().get("api_style"));
        }

        @Test
        void scriptSetupNonVueExtensionReturnsEmpty() {
            // Non-.vue file path — extractScriptSetupName returns null
            String code = "<script setup>\nconst x = 1;\n</script>";
            DetectorResult r = d.detect(new DetectorContext("app.ts", "typescript", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void duplicateComposableNameIsDeduped() {
            String code = """
                    export function useStore() { return {}; }
                    export function useStore() { return {}; }
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
        }

        @Test
        void deterministic() {
            DetectorTestUtils.assertDeterministic(d, ctx("javascript",
                    "export default { name: 'App' }\nexport function useData() {}"));
        }
    }

    // =====================================================================
    // SvelteComponentDetector
    // =====================================================================
    @Nested
    class SvelteCoverage {
        private final SvelteComponentDetector d = new SvelteComponentDetector();

        @Test
        void detectsWithReactiveStatement() {
            String code = """
                    <script>
                    let count = 0;
                    </script>
                    $: doubled = count * 2;
                    <p>{doubled}</p>
                    """;
            DetectorResult r = d.detect(new DetectorContext("Counter.svelte", "svelte", code));
            assertEquals(1, r.nodes().size());
            assertEquals("svelte", r.nodes().get(0).getProperties().get("framework"));
        }

        @Test
        void detectsWithScriptAndHtmlTemplate() {
            String code = """
                    <script>
                    let name = 'World';
                    </script>
                    <h1>Hello {name}!</h1>
                    """;
            DetectorResult r = d.detect(new DetectorContext("Hello.svelte", "svelte", code));
            assertEquals(1, r.nodes().size());
            assertEquals("Hello", r.nodes().get(0).getLabel());
        }

        @Test
        void detectsPropsAndStoresThemInProperties() {
            String code = """
                    <script>
                    export let title;
                    export let count;
                    </script>
                    <h2>{title}: {count}</h2>
                    """;
            DetectorResult r = d.detect(new DetectorContext("Card.svelte", "svelte", code));
            assertEquals(1, r.nodes().size());
            @SuppressWarnings("unchecked")
            List<String> props = (List<String>) r.nodes().get(0).getProperties().get("props");
            assertTrue(props.contains("title"));
            assertTrue(props.contains("count"));
        }

        @Test
        void multipleReactiveStatementsCountedCorrectly() {
            String code = """
                    export let x;
                    $: doubled = x * 2;
                    $: tripled = x * 3;
                    """;
            DetectorResult r = d.detect(new DetectorContext("Reactive.svelte", "svelte", code));
            assertEquals(1, r.nodes().size());
            assertEquals(2, r.nodes().get(0).getProperties().get("reactive_statements"));
        }

        @Test
        void noSvelteSignaturesReturnsEmpty() {
            // Plain JS without Svelte patterns
            String code = "const x = 1;\nfunction foo() { return 42; }";
            DetectorResult r = d.detect(ctx("svelte", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void nullContentReturnsEmpty() {
            DetectorResult r = d.detect(new DetectorContext("A.svelte", "svelte", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void fileNameWithoutExtensionHandled() {
            String code = "export let value;";
            DetectorResult r = d.detect(new DetectorContext("NoExt", "svelte", code));
            assertEquals(1, r.nodes().size());
            // Component name falls back to filename without extension
            assertEquals("NoExt", r.nodes().get(0).getLabel());
        }

        @Test
        void deterministic() {
            DetectorTestUtils.assertDeterministic(d,
                    new DetectorContext("Widget.svelte", "svelte",
                            "export let a;\nexport let b;\n$: sum = a + b;"));
        }
    }

    // =====================================================================
    // AngularComponentDetector
    // =====================================================================
    @Nested
    class AngularCoverage {
        private final AngularComponentDetector d = new AngularComponentDetector();

        @Test
        void detectsComponentWithSelector() {
            String code = """
                    @Component({
                      selector: 'app-sidebar',
                      templateUrl: './sidebar.component.html'
                    })
                    export class SidebarComponent {}
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals("app-sidebar", r.nodes().get(0).getProperties().get("selector"));
            assertEquals("Component", r.nodes().get(0).getProperties().get("decorator"));
        }

        @Test
        void detectsInjectableWithPlatformScope() {
            String code = """
                    @Injectable({
                      providedIn: 'platform'
                    })
                    export class PlatformService {}
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals("platform", r.nodes().get(0).getProperties().get("provided_in"));
            assertEquals(NodeKind.MIDDLEWARE, r.nodes().get(0).getKind());
        }

        @Test
        void detectsDirectiveWithAttributeSelector() {
            String code = """
                    @Directive({
                      selector: '[appTooltip]'
                    })
                    export class TooltipDirective {}
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals("[appTooltip]", r.nodes().get(0).getProperties().get("selector"));
            assertEquals("Directive", r.nodes().get(0).getProperties().get("decorator"));
        }

        @Test
        void detectsPipeWithCustomName() {
            String code = """
                    @Pipe({
                      name: 'truncate'
                    })
                    export class TruncatePipe {}
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals("truncate", r.nodes().get(0).getProperties().get("pipe_name"));
            assertEquals("Pipe", r.nodes().get(0).getProperties().get("decorator"));
        }

        @Test
        void detectsNgModuleWithDeclarations() {
            String code = """
                    @NgModule({
                      declarations: [HomeComponent, NavComponent],
                      bootstrap: [AppComponent]
                    })
                    export class CoreModule {}
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals("NgModule", r.nodes().get(0).getProperties().get("decorator"));
        }

        @Test
        void deduplicatesByClassName() {
            // Same class appearing twice (e.g., decorator appearing on same class) — deduplicated
            String code = """
                    @Component({ selector: 'app-dup' })
                    class DupComponent {}
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
        }

        @Test
        void noAngularDecoratorsReturnsEmpty() {
            String code = "export class PlainService { doSomething() {} }";
            DetectorResult r = d.detect(ctx("typescript", code));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void deterministic() {
            DetectorTestUtils.assertDeterministic(d, ctx("typescript",
                    "@Component({ selector: 'app-root' })\nexport class AppComponent {}"));
        }
    }

    // =====================================================================
    // FrontendRouteDetector
    // =====================================================================
    @Nested
    class FrontendRouteCoverage {
        private final FrontendRouteDetector d = new FrontendRouteDetector();

        @Test
        void detectsReactRouteWithComponentProp() {
            String code = """
                    <Route path="/home" component={HomePage} />
                    <Route path="/profile" component={ProfilePage} />
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(2, r.nodes().size());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void nextjsAppRouterMatchesPageFile() {
            // app/settings/page.tsx -> route /settings
            DetectorResult r = d.detect(
                    new DetectorContext("app/settings/page.tsx", "typescript", "export default function SettingsPage() {}"));
            assertEquals(1, r.nodes().size());
            assertEquals("route /settings", r.nodes().get(0).getLabel());
        }

        @Test
        void nextjsPagesIndexMapsToRoot() {
            DetectorResult r = d.detect(
                    new DetectorContext("pages/index.tsx", "typescript", "export default function Home() {}"));
            assertEquals(1, r.nodes().size());
            assertEquals("route /", r.nodes().get(0).getLabel());
        }

        @Test
        void nextjsNestedPageRoute() {
            DetectorResult r = d.detect(
                    new DetectorContext("pages/blog/[slug].tsx", "typescript", "export default function Post() {}"));
            assertEquals(1, r.nodes().size());
            assertTrue(r.nodes().get(0).getLabel().startsWith("route /blog"));
        }

        @Test
        void vueRouterWithComponentLinks() {
            String code = """
                    const router = createRouter({
                      routes: [
                        { path: '/users', component: UsersPage },
                        { path: '/users/:id', component: UserDetailPage }
                      ]
                    });
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertTrue(r.nodes().size() >= 2);
            // There should be RENDERS edges for components
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void angularRoutesWithComponents() {
            String code = """
                    RouterModule.forRoot([
                      { path: 'home', component: HomeComponent },
                      { path: 'about', component: AboutComponent },
                      { path: 'contact', component: ContactComponent }
                    ])
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertTrue(r.nodes().size() >= 3);
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void angularChildRoutes() {
            String code = """
                    RouterModule.forChild([
                      { path: 'dashboard', component: DashboardComponent }
                    ])
                    """;
            DetectorResult r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
        }

        @Test
        void nullContentReturnsEmpty() {
            DetectorResult r = d.detect(new DetectorContext("test.ts", "typescript", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void vueRoutesColonArrayPatternTriggersDetection() {
            // VUE_ROUTES_ARRAY matches "routes: [" (colon, not equals)
            String code = """
                    const router = {
                      routes: [
                        { path: '/home' }
                      ]
                    };
                    """;
            // VUE_ROUTES_ARRAY matches "routes: [" — Vue detection triggered
            DetectorResult r = d.detect(ctx("typescript", code));
            assertTrue(r.nodes().size() >= 1);
        }

        @Test
        void plainObjectWithPathButNoVuePatternNotDetectedAsVueRoute() {
            // Only { path: ... } without createRouter or routes: keyword
            String code = "const cfg = { path: '/api' };";
            // No createRouter and no "routes:" keyword — Vue detection skipped entirely
            DetectorResult r = d.detect(ctx("typescript", code));
            // Angular and React also won't fire (no RouterModule, no <Route)
            // so result should be 0 nodes
            assertEquals(0, r.nodes().size());
        }

        @Test
        void routeNodePropertiesCorrect() {
            DetectorResult r = d.detect(
                    new DetectorContext("pages/contact.tsx", "typescript", "export default function Contact() {}"));
            assertEquals(1, r.nodes().size());
            assertEquals("frontend_route", r.nodes().get(0).getProperties().get("protocol"));
            assertEquals("nextjs", r.nodes().get(0).getProperties().get("framework"));
            assertEquals("/contact", r.nodes().get(0).getProperties().get("route_path"));
        }

        @Test
        void deterministic() {
            DetectorTestUtils.assertDeterministic(d, ctx("typescript",
                    "<Route path='/a' element={<A />} />\n<Route path='/b' element={<B />} />"));
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }
}
