package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DjangoViewDetectorTest {

    private final DjangoViewDetector detector = new DjangoViewDetector();

    @Test
    void detectsUrlPattern() {
        String code = """
                urlpatterns = [
                    path('api/users/', UserView.as_view(), name='user-list'),
                ]
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("urls.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.ENDPOINT, result.nodes().get(0).getKind());
        assertEquals("api/users/", result.nodes().get(0).getLabel());
        assertEquals("django", result.nodes().get(0).getProperties().get("framework"));
    }

    @Test
    void detectsClassBasedView() {
        String code = """
                class UserView(APIView):
                    def get(self, request):
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.CLASS, result.nodes().get(0).getKind());
        assertEquals("UserView", result.nodes().get(0).getLabel());
    }

    @Test
    void noMatchWithoutUrlpatterns() {
        String code = """
                path('api/users/', UserView.as_view())
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    @Test
    void noMatchOnPlainClass() {
        String code = """
                class UserService(object):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    @Test
    void deterministic() {
        String code = """
                urlpatterns = [
                    path('api/users/', UserView.as_view()),
                ]

                class UserView(APIView):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("urls.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void detectsMultipleUrlPatterns() {
        String code = """
                urlpatterns = [
                    path('api/users/', UserView.as_view()),
                    path('api/orders/', OrderView.as_view()),
                    re_path('^api/products/$', ProductView.as_view()),
                ]
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("urls.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long endpointCount = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENDPOINT).count();
        assertTrue(endpointCount >= 2);
    }

    @Test
    void endpointNodeHasProtocol() {
        String code = """
                urlpatterns = [
                    path('api/items/', ItemView.as_view()),
                ]
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("urls.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var node = result.nodes().get(0);
        assertEquals("REST", node.getProperties().get("protocol"));
        assertNotNull(node.getProperties().get("view_reference"));
    }

    @Test
    void detectsViewSetClass() {
        String code = """
                class ProductViewSet(ModelViewSet):
                    queryset = Product.objects.all()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.CLASS, result.nodes().get(0).getKind());
        assertEquals("ProductViewSet", result.nodes().get(0).getLabel());
    }

    @Test
    void detectsMixinClass() {
        String code = """
                class CacheMixin(CacheMixin, View):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.CLASS, result.nodes().get(0).getKind());
    }

    @Test
    void classBased_hasFrameworkDjango() {
        String code = """
                class ArticleView(DetailView):
                    model = Article
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("django", result.nodes().get(0).getProperties().get("framework"));
        assertEquals("view", result.nodes().get(0).getProperties().get("stereotype"));
    }

    @Test
    void urlPatternViewReferenceExtracted() {
        String code = """
                urlpatterns = [
                    path('users/', views.user_list),
                ]
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("urls.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("views.user_list", result.nodes().get(0).getFqn());
    }

    @Test
    void noMatchOnEmptyContent() {
        DetectorContext ctx = DetectorTestUtils.contextFor("python", "");
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    @Test
    void detectsBothUrlsAndViews() {
        String code = """
                urlpatterns = [
                    path('api/items/', ItemView.as_view()),
                ]

                class ItemView(APIView):
                    def get(self, request):
                        return Response([])
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long endpoints = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENDPOINT).count();
        long classes = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.CLASS).count();
        assertEquals(1, endpoints);
        assertEquals(1, classes);
    }

    @Test
    void classAnnotationIncludesBases() {
        String code = """
                class OrderView(LoginRequiredMixin, APIView):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertFalse(result.nodes().get(0).getAnnotations().isEmpty());
    }

    // ---- Regex fallback path (content > 500KB) ----

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    @Test
    void regexFallback_detectsUrlpatterns() {
        String code = pad("""
                urlpatterns = [
                    path('articles/', views.article_list),
                    path('articles/<int:pk>/', views.article_detail),
                ]
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("urls.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENDPOINT
                && n.getLabel().equals("articles/")),
                "regex fallback should detect urlpatterns endpoint");
        assertEquals("django", result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENDPOINT).findFirst().orElseThrow()
                .getProperties().get("framework"));
    }

    @Test
    void regexFallback_detectsCbv() {
        String code = pad("""
                class ArticleListView(ListView):
                    model = Article
                    template_name = 'articles/list.html'
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS
                && "ArticleListView".equals(n.getLabel())),
                "regex fallback should detect class-based view");
        assertEquals("django", result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.CLASS).findFirst().orElseThrow()
                .getProperties().get("framework"));
    }

    @Test
    void regexFallback_detectsMultipleEndpoints() {
        String code = pad("""
                urlpatterns = [
                    path('users/', views.user_list),
                    path('users/<int:pk>/', views.user_detail),
                    path('users/create/', views.user_create),
                ]
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("urls.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long endpointCount = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count();
        assertTrue(endpointCount >= 3, "regex fallback should detect all url patterns");
    }

    @Test
    void regexFallback_noMatch_returnsEmpty() {
        String code = pad("""
                from django.db import models

                class SomeModel(models.Model):
                    name = models.CharField(max_length=100)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENDPOINT).count(),
                "regex fallback should not detect endpoints in models file");
    }
}
