package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SQLAlchemyModelDetectorExtendedTest {

    private final SQLAlchemyModelDetector detector = new SQLAlchemyModelDetector();

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    // ---- mapped_column (SQLAlchemy 2.0 style) ----

    @Test
    void detectsMappedColumnStyle() {
        String code = """
                class Article(Base):
                    __tablename__ = 'articles'
                    id: Mapped[int] = mapped_column(primary_key=True)
                    title: Mapped[str] = mapped_column(String(200))
                    body: Mapped[str] = mapped_column(Text)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) entity.getProperties().get("columns");
        assertNotNull(columns);
        assertTrue(columns.contains("id"));
        assertTrue(columns.contains("title"));
        assertTrue(columns.contains("body"));
    }

    @Test
    void regexFallback_detectsMappedColumn() {
        String code = pad("""
                class Post(Base):
                    __tablename__ = 'posts'
                    id: Mapped[int] = mapped_column(primary_key=True)
                    slug: Mapped[str] = mapped_column(String(100), unique=True)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.ENTITY && "Post".equals(n.getLabel())));
        var entity = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) entity.getProperties().get("columns");
        assertNotNull(columns);
        assertTrue(columns.contains("id"));
        assertTrue(columns.contains("slug"));
    }

    // ---- DeclarativeBase inheritance ----

    @Test
    void detectsDeclarativeBaseInheritance() {
        String code = """
                class Customer(DeclarativeBase):
                    __tablename__ = 'customers'
                    id = Column(Integer, primary_key=True)
                    email = Column(String, unique=True)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.ENTITY && "Customer".equals(n.getLabel())));
        var entity = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("customers", entity.getProperties().get("table_name"));
        assertEquals("sqlalchemy", entity.getProperties().get("framework"));
    }

    @Test
    void regexFallback_detectsDeclarativeBase() {
        String code = pad("""
                class Supplier(DeclarativeBase):
                    __tablename__ = 'suppliers'
                    id = Column(Integer, primary_key=True)
                    name = Column(String)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.ENTITY && "Supplier".equals(n.getLabel())));
    }

    // ---- relationship() with backref ----

    @Test
    void detectsRelationshipWithBackref() {
        String code = """
                class Author(Base):
                    __tablename__ = 'authors'
                    id = Column(Integer, primary_key=True)
                    name = Column(String)
                    books = relationship("Book", backref="author")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO));
        var mapsToEdge = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.MAPS_TO).findFirst().orElseThrow();
        assertNotNull(mapsToEdge.getTarget());
        assertEquals("Book", mapsToEdge.getTarget().getLabel());
    }

    @Test
    void detectsRelationshipWithBackPopulates() {
        String code = """
                class Post(Base):
                    __tablename__ = 'posts'
                    id = Column(Integer, primary_key=True)
                    author_id = Column(Integer, ForeignKey('authors.id'))
                    author = relationship("Author", back_populates="posts")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO));
    }

    @Test
    void regexFallback_detectsRelationshipWithBackref() {
        String code = pad("""
                class Department(Base):
                    __tablename__ = 'departments'
                    id = Column(Integer, primary_key=True)
                    employees = relationship('Employee', backref='department')
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO));
    }

    // ---- Column with various types ----

    @Test
    void detectsColumnsWithVariousTypes() {
        String code = """
                class OrderItem(Base):
                    __tablename__ = 'order_items'
                    id = Column(Integer, primary_key=True)
                    product_id = Column(Integer, ForeignKey('products.id'))
                    quantity = Column(Integer, nullable=False)
                    unit_price = Column(Float)
                    notes = Column(String(500), nullable=True)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) entity.getProperties().get("columns");
        assertNotNull(columns);
        assertTrue(columns.contains("id"));
        assertTrue(columns.contains("product_id"));
        assertTrue(columns.contains("quantity"));
        assertTrue(columns.contains("unit_price"));
        assertTrue(columns.contains("notes"));
    }

    @Test
    void regexFallback_detectsColumnsWithTypes() {
        String code = pad("""
                class Inventory(Base):
                    __tablename__ = 'inventory'
                    sku = Column(String(50), primary_key=True)
                    qty = Column(Integer)
                    price = Column(Float)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) entity.getProperties().get("columns");
        assertNotNull(columns);
        assertFalse(columns.isEmpty());
    }

    // ---- Default table name (pluralized class name) ----

    @Test
    void defaultTableNameWhenNoTablenameDefined() {
        String code = """
                class Widget(Base):
                    id = Column(Integer, primary_key=True)
                    name = Column(String)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("widgets", entity.getProperties().get("table_name"));
    }

    // ---- Multiple models share one db node ----

    @Test
    void multipleModelsShareSingleDatabaseNode() {
        String code = """
                class Alpha(Base):
                    __tablename__ = 'alpha'
                    id = Column(Integer, primary_key=True)

                class Beta(Base):
                    __tablename__ = 'beta'
                    id = Column(Integer, primary_key=True)

                class Gamma(Base):
                    __tablename__ = 'gamma'
                    id = Column(Integer, primary_key=True)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long dbNodes = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.DATABASE_CONNECTION).count();
        assertEquals(1, dbNodes);

        long entityNodes = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).count();
        assertEquals(3, entityNodes);
    }

    // ---- CONNECTS_TO edge ----

    @Test
    void connectsToEdgeExistsForEachModel() {
        String code = """
                class X(Base):
                    __tablename__ = 'x'
                    id = Column(Integer)

                class Y(Base):
                    __tablename__ = 'y'
                    id = Column(Integer)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long connectsToEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.CONNECTS_TO).count();
        assertEquals(2, connectsToEdges);
    }

    // ---- Multiple relationships ----

    @Test
    void multipleRelationshipsCreateMultipleMapsToEdges() {
        String code = """
                class Blog(Base):
                    __tablename__ = 'blogs'
                    id = Column(Integer, primary_key=True)
                    posts = relationship("Post", backref="blog")
                    authors = relationship("Author", secondary="blog_authors")
                    comments = relationship("Comment")
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long mapsToEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.MAPS_TO).count();
        assertEquals(3, mapsToEdges);
    }

    // ---- Empty file ----

    @Test
    void emptyFileReturnsEmpty() {
        DetectorContext ctx = DetectorTestUtils.contextFor("python", "");
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    // ---- Framework property ----

    @Test
    void frameworkPropertyIsSqlalchemy() {
        String code = """
                class Item(Base):
                    __tablename__ = 'items'
                    id = Column(Integer, primary_key=True)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertEquals("sqlalchemy", entity.getProperties().get("framework"));
    }

    // ---- FQN ----

    @Test
    void fqnContainsClassNameAndFilePath() {
        String code = """
                class Token(Base):
                    __tablename__ = 'tokens'
                    id = Column(Integer, primary_key=True)
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("auth/models.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var entity = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        assertNotNull(entity.getFqn());
        assertTrue(entity.getFqn().contains("Token"));
    }

    // ---- Determinism ----

    @Test
    void deterministicWithRelationships() {
        String code = """
                class User(Base):
                    __tablename__ = 'users'
                    id = Column(Integer, primary_key=True)
                    orders = relationship("Order", back_populates="user")
                    profile = relationship("Profile", uselist=False)

                class Order(Base):
                    __tablename__ = 'orders'
                    id = Column(Integer, primary_key=True)
                    user_id = Column(Integer, ForeignKey('users.id'))
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void regexFallback_deterministicWithMixedStyles() {
        String code = pad("""
                class Product(Base):
                    __tablename__ = 'products'
                    id: Mapped[int] = mapped_column(primary_key=True)
                    name: Mapped[str] = mapped_column(String(100))
                    category = relationship('Category', backref='products')

                class Category(Base):
                    __tablename__ = 'categories'
                    id = Column(Integer, primary_key=True)
                    name = Column(String(50))
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("models.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
