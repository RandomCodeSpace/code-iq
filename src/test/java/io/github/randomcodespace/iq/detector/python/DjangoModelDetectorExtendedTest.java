package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DjangoModelDetectorExtendedTest {

    private final DjangoModelDetector detector = new DjangoModelDetector();

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    // ---- ManyToManyField ----

    @Test
    void detectsManyToManyField() {
        String code = """
                class Article(models.Model):
                    tags = models.ManyToManyField("Tag")
                    categories = models.ManyToManyField("Category")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY && "Article".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertEquals("django", entity.getProperties().get("framework"));

        // 2 M2M DEPENDS_ON edges + 1 CONNECTS_TO
        long dependsOn = result.edges().stream().filter(e -> e.getKind() == EdgeKind.DEPENDS_ON).count();
        assertEquals(2, dependsOn);
    }

    @Test
    void regexFallback_detectsManyToManyField() {
        String code = pad("""
                class Course(models.Model):
                    students = models.ManyToManyField("Student")
                    title = models.CharField(max_length=200)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.ENTITY && "Course".equals(n.getLabel())),
                "regex fallback should detect model with M2M field");
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    // ---- ForeignKey ----

    @Test
    void detectsForeignKeyDependsOn() {
        String code = """
                class Comment(models.Model):
                    post = models.ForeignKey("Post", on_delete=models.CASCADE)
                    author = models.ForeignKey("User", on_delete=models.SET_NULL)
                    body = models.TextField()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long dependsEdges = result.edges().stream().filter(e -> e.getKind() == EdgeKind.DEPENDS_ON).count();
        assertEquals(2, dependsEdges);
    }

    @Test
    void regexFallback_detectsForeignKey() {
        String code = pad("""
                class Order(models.Model):
                    customer = models.ForeignKey("Customer", on_delete=models.CASCADE)
                    amount = models.DecimalField()
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    // ---- OneToOneField ----

    @Test
    void detectsOneToOneField() {
        String code = """
                class UserProfile(models.Model):
                    user = models.OneToOneField("User", on_delete=models.CASCADE)
                    bio = models.TextField(blank=True)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY && "UserProfile".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertNotNull(entity);
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void regexFallback_detectsOneToOneField() {
        String code = pad("""
                class Settings(models.Model):
                    user = models.OneToOneField("User", on_delete=models.CASCADE)
                    theme = models.CharField(max_length=50)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    // ---- Abstract model ----

    @Test
    void detectsModelWithAbstractMeta() {
        String code = """
                class TimestampedModel(models.Model):
                    created_at = models.DateTimeField(auto_now_add=True)
                    updated_at = models.DateTimeField(auto_now=True)

                    class Meta:
                        abstract = True
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        // Abstract models are still detected as entities
        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.ENTITY && "TimestampedModel".equals(n.getLabel())));
    }

    // ---- Verbose name in Meta ----

    @Test
    void detectsModelWithVerboseName() {
        String code = """
                class BlogPost(models.Model):
                    title = models.CharField(max_length=200)

                    class Meta:
                        verbose_name = 'blog post'
                        verbose_name_plural = 'blog posts'
                        db_table = 'blog_post'
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("blog_post", entity.getProperties().get("table_name"));
    }

    // ---- db_table override ----

    @Test
    void detectsDbTableOverride() {
        String code = """
                class Invoice(models.Model):
                    number = models.CharField(max_length=50)
                    amount = models.DecimalField()

                    class Meta:
                        db_table = 'billing_invoices'
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("billing_invoices", entity.getProperties().get("table_name"));
    }

    @Test
    void regexFallback_detectsDbTableOverride() {
        String code = pad("""
                class Event(models.Model):
                    name = models.CharField(max_length=200)

                    class Meta:
                        db_table = 'calendar_events'
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("calendar_events", entity.getProperties().get("table_name"));
    }

    // ---- Class not extending models.Model ----

    @Test
    void noMatchOnServiceClass() {
        String code = """
                class UserService:
                    def create_user(self, data):
                        pass

                    def get_user(self, pk):
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("services.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    @Test
    void noMatchOnClassExtendingArbitraryBase() {
        String code = """
                class MyView(View):
                    def get(self, request):
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("views.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).count());
    }

    @Test
    void regexFallback_noMatchOnNonModel() {
        String code = pad("""
                class Serializer(BaseSerializer):
                    def to_dict(self):
                        pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("serializers.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).count());
    }

    // ---- Fields map ----

    @Test
    void detectsAllFieldTypes() {
        String code = """
                class Product(models.Model):
                    name = models.CharField(max_length=100)
                    description = models.TextField()
                    price = models.DecimalField()
                    active = models.BooleanField(default=True)
                    created = models.DateTimeField(auto_now_add=True)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) entity.getProperties().get("fields");
        assertNotNull(fields);
        assertTrue(fields.containsKey("name"));
        assertTrue(fields.containsKey("description"));
        assertTrue(fields.containsKey("price"));
        assertTrue(fields.containsKey("active"));
        assertTrue(fields.containsKey("created"));
    }

    // ---- Manager with associated model ----

    @Test
    void detectsManagerAssignedToModel() {
        String code = """
                class PublishedManager(models.Manager):
                    def get_queryset(self):
                        return super().get_queryset().filter(published=True)

                class Article(models.Model):
                    title = models.CharField(max_length=200)
                    published = models.BooleanField()
                    objects = PublishedManager()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.REPOSITORY));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENTITY));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.QUERIES));
    }

    @Test
    void regexFallback_detectsManagerAssigned() {
        String code = pad("""
                class ActiveManager(models.Manager):
                    pass

                class Post(models.Model):
                    title = models.CharField(max_length=200)
                    objects = ActiveManager()
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.REPOSITORY));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.QUERIES));
    }

    // ---- Ordering in Meta ----

    @Test
    void detectsMetaOrderingProperty() {
        String code = """
                class Post(models.Model):
                    title = models.CharField(max_length=200)
                    created = models.DateTimeField()

                    class Meta:
                        ordering = ['-created', 'title']
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertNotNull(entity.getProperties().get("ordering"));
    }

    // ---- Determinism ----

    @Test
    void deterministicWithComplexModel() {
        String code = """
                class Category(models.Manager):
                    pass

                class Product(models.Model):
                    name = models.CharField(max_length=100)
                    category = models.ForeignKey("Category", on_delete=models.SET_NULL, null=True)
                    tags = models.ManyToManyField("Tag")

                    class Meta:
                        db_table = 'shop_products'
                        ordering = ['name']
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void regexFallback_deterministicOnMultipleModels() {
        String code = pad("""
                class User(models.Model):
                    username = models.CharField(max_length=100)

                class Post(models.Model):
                    author = models.ForeignKey("User", on_delete=models.CASCADE)
                    title = models.CharField(max_length=200)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
