package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SQLAlchemyModelDetectorTest {

    private final SQLAlchemyModelDetector detector = new SQLAlchemyModelDetector();

    @Test
    void detectsModel() {
        String code = """
                class User(Base):
                    __tablename__ = 'users'
                    id = Column(Integer, primary_key=True)
                    name = Column(String)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size()); // entity + database:unknown
        var entityNode = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("User", entityNode.getLabel());
        assertEquals("users", entityNode.getProperties().get("table_name"));
        assertEquals("sqlalchemy", entityNode.getProperties().get("framework"));
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) entityNode.getProperties().get("columns");
        assertTrue(columns.contains("id"));
        assertTrue(columns.contains("name"));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO));
    }

    @Test
    void detectsRelationship() {
        String code = """
                class User(Base):
                    __tablename__ = 'users'
                    id = Column(Integer, primary_key=True)
                    orders = relationship("Order", back_populates="user")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size()); // entity + database:unknown
        assertEquals(2, result.edges().size()); // MAPS_TO + CONNECTS_TO
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONNECTS_TO));
    }

    @Test
    void defaultTableNameWhenMissing() {
        String code = """
                class Product(Base):
                    id = Column(Integer, primary_key=True)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size()); // entity + database:unknown
        var entityNode = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("products", entityNode.getProperties().get("table_name"));
    }

    @Test
    void noMatchOnPlainClass() {
        String code = """
                class MyService:
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    @Test
    void deterministic() {
        String code = """
                class User(Base):
                    __tablename__ = 'users'
                    id = Column(Integer, primary_key=True)
                    orders = relationship("Order")

                class Order(Base):
                    __tablename__ = 'orders'
                    id = Column(Integer, primary_key=True)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void detectsDeclarativeBaseModel() {
        String code = """
                class Category(DeclarativeBase):
                    __tablename__ = 'categories'
                    id = Column(Integer, primary_key=True)
                    name = Column(String)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var entityNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("categories", entityNode.getProperties().get("table_name"));
    }

    @Test
    void detectsMappedColumns() {
        String code = """
                class Article(Base):
                    __tablename__ = 'articles'
                    id: Mapped[int] = mapped_column(primary_key=True)
                    title: Mapped[str] = mapped_column(String(200))
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var entityNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) entityNode.getProperties().get("columns");
        assertNotNull(columns);
        assertTrue(columns.contains("id"));
        assertTrue(columns.contains("title"));
    }

    @Test
    void multipleRelationshipsCreateMultipleEdges() {
        String code = """
                class User(Base):
                    __tablename__ = 'users'
                    id = Column(Integer, primary_key=True)
                    posts = relationship("Post")
                    comments = relationship("Comment")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        long mapsToEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.MAPS_TO).count();
        assertEquals(2, mapsToEdges);
    }

    @Test
    void databaseNodeKindIsDbConnection() {
        String code = """
                class Customer(Base):
                    __tablename__ = 'customers'
                    id = Column(Integer, primary_key=True)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var dbNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.DATABASE_CONNECTION).findFirst();
        assertTrue(dbNode.isPresent());
    }

    @Test
    void noMatchOnEmptyContent() {
        DetectorContext ctx = DetectorTestUtils.contextFor("python", "");
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    @Test
    void detectsModelBase() {
        String code = """
                class Tag(Model):
                    __tablename__ = 'tags'
                    id = Column(Integer, primary_key=True)
                    name = Column(String)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var entityNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("Tag", entityNode.getLabel());
    }

    @Test
    void databaseCreatedOnce() {
        String code = """
                class A(Base):
                    __tablename__ = 'a'
                    id = Column(Integer)

                class B(Base):
                    __tablename__ = 'b'
                    id = Column(Integer)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        long dbCount = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.DATABASE_CONNECTION).count();
        assertEquals(1, dbCount);
    }

    // ---- Regex fallback path (content > 500KB) ----

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    @Test
    void regexFallback_detectsBaseModel() {
        String code = pad("""
                class User(Base):
                    __tablename__ = 'users'
                    id = mapped_column(Integer, primary_key=True)
                    name = mapped_column(String(100))
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENTITY
                && "User".equals(n.getLabel())),
                "regex fallback should detect SQLAlchemy model");
        assertEquals("sqlalchemy", result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow()
                .getProperties().get("framework"));
    }

    @Test
    void regexFallback_extractsTableName() {
        String code = pad("""
                class Order(Base):
                    __tablename__ = 'shop_orders'
                    id = mapped_column(Integer, primary_key=True)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("shop_orders", entity.getProperties().get("table_name"));
    }

    @Test
    void regexFallback_detectsRelationship() {
        String code = pad("""
                class Post(Base):
                    __tablename__ = 'posts'
                    id = mapped_column(Integer, primary_key=True)
                    author = relationship('User', back_populates='posts')
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO),
                "regex fallback should detect relationship as MAPS_TO edge");
    }

    @Test
    void regexFallback_extractsColumns() {
        String code = pad("""
                class Product(Base):
                    __tablename__ = 'products'
                    id = mapped_column(Integer, primary_key=True)
                    name = mapped_column(String)
                    price = Column(Float)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        var columns = (List<?>) entity.getProperties().get("columns");
        assertNotNull(columns);
        assertFalse(columns.isEmpty(), "regex fallback should extract columns");
    }
}
