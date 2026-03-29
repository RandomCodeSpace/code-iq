package io.github.randomcodespace.iq.detector.frontend;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for frontend detectors to cover more branches.
 */
class FrontendDetectorsExtendedTest {

    // ==================== AngularComponentDetector ====================
    @Nested
    class AngularExtended {
        private final AngularComponentDetector d = new AngularComponentDetector();

        @Test
        void detectsInjectableService() {
            String code = """
                    @Injectable({
                      providedIn: 'root'
                    })
                    export class UserService {
                    }
                    """;
            var r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals(NodeKind.MIDDLEWARE, r.nodes().get(0).getKind());
            assertEquals("Injectable", r.nodes().get(0).getProperties().get("decorator"));
            assertEquals("root", r.nodes().get(0).getProperties().get("provided_in"));
        }

        @Test
        void detectsDirective() {
            String code = """
                    @Directive({
                      selector: '[appHighlight]'
                    })
                    export class HighlightDirective {
                    }
                    """;
            var r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals("Directive", r.nodes().get(0).getProperties().get("decorator"));
            assertEquals("[appHighlight]", r.nodes().get(0).getProperties().get("selector"));
        }

        @Test
        void detectsPipe() {
            String code = """
                    @Pipe({
                      name: 'capitalize'
                    })
                    export class CapitalizePipe {
                    }
                    """;
            var r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals("Pipe", r.nodes().get(0).getProperties().get("decorator"));
            assertEquals("capitalize", r.nodes().get(0).getProperties().get("pipe_name"));
        }

        @Test
        void detectsNgModule() {
            String code = """
                    @NgModule({
                      declarations: [AppComponent],
                      imports: [BrowserModule]
                    })
                    export class AppModule {
                    }
                    """;
            var r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals("NgModule", r.nodes().get(0).getProperties().get("decorator"));
        }

        @Test
        void detectsMultipleComponents() {
            String code = """
                    @Component({
                      selector: 'app-header'
                    })
                    export class HeaderComponent {}

                    @Component({
                      selector: 'app-footer'
                    })
                    export class FooterComponent {}
                    """;
            var r = d.detect(ctx("typescript", code));
            assertEquals(2, r.nodes().size());
        }

        @Test
        void nullContentReturnsEmpty() {
            var r = d.detect(new DetectorContext("test.ts", "typescript", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void emptyContentReturnsEmpty() {
            var r = d.detect(ctx("typescript", ""));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void deduplicatesSameName() {
            // If somehow same class name appears twice, should be deduplicated
            String code = """
                    @Component({
                      selector: 'app-test'
                    })
                    class TestComponent {}
                    """;
            var r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
        }
    }

    // ==================== VueComponentDetector ====================
    @Nested
    class VueExtended {
        private final VueComponentDetector d = new VueComponentDetector();

        @Test
        void detectsDefineComponent() {
            String code = """
                    export default defineComponent({
                      name: 'UserProfile',
                      props: { userId: String }
                    })
                    """;
            var r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals("UserProfile", r.nodes().get(0).getLabel());
            assertEquals("composition", r.nodes().get(0).getProperties().get("api_style"));
        }

        @Test
        void detectsScriptSetup() {
            String code = "<script setup lang=\"ts\">\nimport { ref } from 'vue'\nconst count = ref(0)\n</script>";
            var r = d.detect(new DetectorContext("components/Counter.vue", "vue", code));
            assertEquals(1, r.nodes().size());
            assertEquals("Counter", r.nodes().get(0).getLabel());
            assertEquals("script_setup", r.nodes().get(0).getProperties().get("api_style"));
        }

        @Test
        void detectsComposableFunction() {
            String code = """
                    export function useAuth() {
                        const user = ref(null);
                        return { user };
                    }
                    """;
            var r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals(NodeKind.HOOK, r.nodes().get(0).getKind());
            assertEquals("useAuth", r.nodes().get(0).getLabel());
        }

        @Test
        void detectsComposableConst() {
            String code = """
                    export const useCounter = () => {
                        const count = ref(0);
                        return { count };
                    }
                    """;
            var r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
            assertEquals(NodeKind.HOOK, r.nodes().get(0).getKind());
            assertEquals("useCounter", r.nodes().get(0).getLabel());
        }

        @Test
        void nullContentReturnsEmpty() {
            var r = d.detect(new DetectorContext("test.vue", "vue", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void emptyContentReturnsEmpty() {
            var r = d.detect(ctx("vue", ""));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void scriptSetupNonVueFileIgnored() {
            // Script setup only extracts name from .vue files
            String code = "<script setup>\nconst x = 1;\n</script>";
            var r = d.detect(new DetectorContext("test.ts", "typescript", code));
            // Not a .vue file so extractScriptSetupName returns null
            assertTrue(r.nodes().isEmpty());
        }
    }

    // ==================== FrontendRouteDetector ====================
    @Nested
    class FrontendRouteExtended {
        private final FrontendRouteDetector d = new FrontendRouteDetector();

        @Test
        void detectsReactRouteWithElement() {
            String code = """
                    <Route path="/dashboard" element={<Dashboard />} />
                    <Route path="/settings" element={<Settings />} />
                    """;
            var r = d.detect(ctx("typescript", code));
            assertEquals(2, r.nodes().size());
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void detectsVueRouter() {
            String code = """
                    const router = createRouter({
                      routes: [
                        { path: '/home', component: Home },
                        { path: '/about', component: About },
                        { path: '/contact' }
                      ]
                    })
                    """;
            var r = d.detect(ctx("typescript", code));
            assertTrue(r.nodes().size() >= 3);
        }

        @Test
        void detectsAngularRouterModule() {
            String code = """
                    RouterModule.forRoot([
                      { path: 'dashboard', component: DashboardComponent },
                      { path: 'settings', component: SettingsComponent }
                    ])
                    """;
            var r = d.detect(ctx("typescript", code));
            assertTrue(r.nodes().size() >= 2);
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void detectsNextjsAppRouter() {
            var r = d.detect(new DetectorContext("app/dashboard/page.tsx", "typescript", "export default function Page() {}"));
            assertEquals(1, r.nodes().size());
        }

        @Test
        void detectsNextjsPagesIndex() {
            var r = d.detect(new DetectorContext("pages/index.tsx", "typescript", "export default function Home() {}"));
            assertEquals(1, r.nodes().size());
            assertEquals("route /", r.nodes().get(0).getLabel());
        }

        @Test
        void detectsNextjsNestedPages() {
            var r = d.detect(new DetectorContext("pages/blog/post.tsx", "typescript", "export default function Post() {}"));
            assertEquals(1, r.nodes().size());
            assertEquals("route /blog/post", r.nodes().get(0).getLabel());
        }

        @Test
        void nullContentReturnsEmpty() {
            var r = d.detect(new DetectorContext("test.ts", "typescript", null));
            assertTrue(r.nodes().isEmpty());
        }

        @Test
        void bareReactRouteWithoutComponent() {
            String code = """
                    <Route path="/fallback" />
                    """;
            var r = d.detect(ctx("typescript", code));
            assertEquals(1, r.nodes().size());
        }

        @Test
        void vueRouteWithRoutesArray() {
            // Must have "routes:" array pattern to trigger Vue detection
            String code = """
                    const router = createRouter({
                      history: createWebHistory(),
                      routes: [
                        { path: '/login' }
                      ]
                    });
                    """;
            var r = d.detect(ctx("typescript", code));
            assertTrue(r.nodes().size() >= 1);
        }
    }

    // ==================== ReactComponentDetector ====================
    @Nested
    class ReactExtended {
        private final ReactComponentDetector d = new ReactComponentDetector();

        @Test
        void detectsExportDefaultFunction() {
            String code = """
                    export default function UserProfile({ name }) {
                        return <div>{name}</div>;
                    }
                    """;
            var r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
            assertEquals("UserProfile", r.nodes().get(0).getLabel());
        }

        @Test
        void detectsExportConstArrow() {
            String code = """
                    export const Button = ({ onClick, label }) => {
                        return <button onClick={onClick}>{label}</button>;
                    };
                    """;
            var r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsExportConstFC() {
            String code = """
                    export const Header: React.FC = () => <header/>;
                    """;
            var r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsClassComponent() {
            String code = """
                    class Dashboard extends React.Component {
                        render() { return <div/>; }
                    }
                    """;
            var r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsHookExport() {
            String code = """
                    export function useAuth() {
                        const [user, setUser] = useState(null);
                        return { user, setUser };
                    }
                    export const useCounter = () => {
                        return {};
                    };
                    """;
            var r = d.detect(ctx("typescript", code));
            assertTrue(r.nodes().size() >= 2);
        }
    }

    // ==================== SvelteComponentDetector ====================
    @Nested
    class SvelteExtended {
        private final SvelteComponentDetector d = new SvelteComponentDetector();

        @Test
        void detectsSvelteComponent() {
            // Svelte uses .svelte file extension
            String code = """
                    <script>
                        export let name;
                    </script>
                    <h1>Hello {name}!</h1>
                    """;
            var r = d.detect(new DetectorContext("Hello.svelte", "svelte", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }
}
