package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DjangoModelDetectorTest {

    private final DjangoModelDetector detector = new DjangoModelDetector();

    @Test
    void detectsDjangoModel() {
        String code = """
                class User(models.Model):
                    name = models.CharField(max_length=100)
                    email = models.EmailField()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size()); // entity + database:unknown
        var entityNode = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("User", entityNode.getLabel());
        assertEquals("django", entityNode.getProperties().get("framework"));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO));
    }

    @Test
    void detectsForeignKeyRelationship() {
        String code = """
                class Order(models.Model):
                    user = models.ForeignKey("User", on_delete=models.CASCADE)
                    total = models.DecimalField()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size()); // entity + database:unknown
        assertEquals(2, result.edges().size()); // DEPENDS_ON + CONNECTS_TO
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO));
    }

    @Test
    void detectsManager() {
        String code = """
                class ActiveManager(models.Manager):
                    pass

                class Item(models.Model):
                    name = models.CharField(max_length=50)
                    objects = ActiveManager()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        // 1 manager + 1 model + 1 database:unknown = 3 nodes
        assertEquals(3, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.REPOSITORY));
        // manager assignment edge
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.QUERIES));
    }

    @Test
    void noMatchOnPlainClass() {
        String code = """
                class MyService:
                    def do_thing(self):
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    @Test
    void deterministic() {
        String code = """
                class User(models.Model):
                    name = models.CharField(max_length=100)

                class Order(models.Model):
                    user = models.ForeignKey("User", on_delete=models.CASCADE)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void detectsMetaTableName() {
        String code = """
                class Post(models.Model):
                    title = models.CharField(max_length=200)

                    class Meta:
                        db_table = 'blog_posts'
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entityNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("blog_posts", entityNode.getProperties().get("table_name"));
    }

    @Test
    void detectsMetaOrdering() {
        String code = """
                class Article(models.Model):
                    title = models.CharField(max_length=200)
                    created = models.DateTimeField()

                    class Meta:
                        ordering = ['-created']
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entityNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertNotNull(entityNode.getProperties().get("ordering"));
    }

    @Test
    void detectsManyToManyRelationship() {
        String code = """
                class Article(models.Model):
                    tags = models.ManyToManyField("Tag")
                    title = models.CharField(max_length=200)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void detectsModelFields() {
        String code = """
                class Product(models.Model):
                    name = models.CharField(max_length=100)
                    price = models.DecimalField()
                    stock = models.IntegerField()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entityNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) entityNode.getProperties().get("fields");
        assertNotNull(fields);
        assertTrue(fields.containsKey("name"));
        assertTrue(fields.containsKey("price"));
        assertTrue(fields.containsKey("stock"));
    }

    @Test
    void detectsManagerWithType() {
        String code = """
                class PublishedManager(models.Manager):
                    def get_queryset(self):
                        return super().get_queryset().filter(published=True)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var managerNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.REPOSITORY).findFirst().orElseThrow();
        assertEquals("PublishedManager", managerNode.getLabel());
        assertEquals("manager", managerNode.getProperties().get("type"));
        assertEquals("django", managerNode.getProperties().get("framework"));
    }

    @Test
    void detectsMultipleModels() {
        String code = """
                class User(models.Model):
                    name = models.CharField(max_length=100)

                class Profile(models.Model):
                    user = models.OneToOneField("User", on_delete=models.CASCADE)
                    bio = models.TextField()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long entityCount = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).count();
        assertEquals(2, entityCount);
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void noMatchOnEmptyContent() {
        DetectorContext ctx = DetectorTestUtils.contextFor("python", "");
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    @Test
    void databaseNodeCreatedOnce() {
        String code = """
                class User(models.Model):
                    name = models.CharField(max_length=100)

                class Order(models.Model):
                    total = models.DecimalField()
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long dbNodes = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.DATABASE_CONNECTION).count();
        assertEquals(1, dbNodes);
    }

    @Test
    void oneToOneFieldCreatesEdge() {
        String code = """
                class Profile(models.Model):
                    user = models.OneToOneField("User", on_delete=models.CASCADE)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    // ---- Regex fallback path (content > 500KB) ----

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    @Test
    void regexFallback_detectsDjangoModel() {
        String code = pad("""
                class Article(models.Model):
                    title = models.CharField(max_length=200)
                    body = models.TextField()
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("blog/models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENTITY && "Article".equals(n.getLabel())),
                "regex fallback should detect Django model");
        assertEquals("django", result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow()
                .getProperties().get("framework"));
    }

    @Test
    void regexFallback_detectsManager() {
        String code = pad("""
                class PublishedManager(models.Manager):
                    def get_queryset(self):
                        return super().get_queryset().filter(published=True)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("blog/models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.REPOSITORY),
                "regex fallback should detect manager");
        assertEquals("manager", result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.REPOSITORY).findFirst().orElseThrow()
                .getProperties().get("type"));
    }

    @Test
    void regexFallback_detectsModelWithMetaTableName() {
        String code = pad("""
                class BlogPost(models.Model):
                    title = models.CharField(max_length=200)

                    class Meta:
                        db_table = 'blog_post_table'
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("blog/models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("blog_post_table", entity.getProperties().get("table_name"));
    }

    @Test
    void regexFallback_emptyContent_returnsEmpty() {
        String code = "\n" + "#\n".repeat(260_000);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).count());
    }
}
