package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PydanticModelDetectorTest {

    private final PydanticModelDetector detector = new PydanticModelDetector();

    @Test
    void detectsBaseModel() {
        String code = """
                class User(BaseModel):
                    name: str
                    age: int
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.ENTITY, result.nodes().get(0).getKind());
        assertEquals("User", result.nodes().get(0).getLabel());
        assertEquals("pydantic", result.nodes().get(0).getProperties().get("framework"));
        assertEquals("BaseModel", result.nodes().get(0).getProperties().get("base_class"));
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) result.nodes().get(0).getProperties().get("fields");
        assertTrue(fields.contains("name"));
        assertTrue(fields.contains("age"));
    }

    @Test
    void detectsBaseSettings() {
        String code = """
                class AppSettings(BaseSettings):
                    debug: bool
                    db_url: str
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.CONFIG_DEFINITION, result.nodes().get(0).getKind());
    }

    @Test
    void detectsInheritance() {
        String code = """
                class Base(BaseModel):
                    id: int

                class User(Base):
                    name: str
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals("Base", result.nodes().get(0).getLabel());
    }

    @Test
    void noMatchOnPlainClass() {
        String code = """
                class MyService:
                    def run(self):
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    @Test
    void deterministic() {
        String code = """
                class Item(BaseModel):
                    name: str
                    price: float

                class Config(BaseSettings):
                    api_key: str
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void detectsValidators() {
        String code = """
                class User(BaseModel):
                    name: str
                    age: int

                    @validator('age')
                    def age_must_be_positive(cls, v):
                        return v
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        List<String> annotations = result.nodes().get(0).getAnnotations();
        assertNotNull(annotations);
        assertTrue(annotations.contains("age"));
    }

    @Test
    void detectsFieldValidator() {
        String code = """
                class Product(BaseModel):
                    price: float

                    @field_validator('price')
                    def price_positive(cls, v):
                        return v
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        List<String> annotations = result.nodes().get(0).getAnnotations();
        assertTrue(annotations.contains("price"));
    }

    @Test
    void detectsConfigClass() {
        String code = """
                class MyModel(BaseModel):
                    name: str

                    class Config:
                        orm_mode = True
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        @SuppressWarnings("unchecked")
        Map<String, String> config = (Map<String, String>) result.nodes().get(0).getProperties().get("config");
        assertNotNull(config);
        assertEquals("True", config.get("orm_mode"));
    }

    @Test
    void detectsFieldTypes() {
        String code = """
                class Order(BaseModel):
                    id: int
                    items: List[str]
                    total: float
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        @SuppressWarnings("unchecked")
        Map<String, String> fieldTypes = (Map<String, String>) result.nodes().get(0).getProperties().get("field_types");
        assertNotNull(fieldTypes);
        assertTrue(fieldTypes.containsKey("id"));
        assertTrue(fieldTypes.containsKey("total"));
    }

    @Test
    void detectsMultipleModels() {
        String code = """
                class UserCreate(BaseModel):
                    name: str
                    email: str

                class UserResponse(BaseModel):
                    id: int
                    name: str
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        assertTrue(result.nodes().stream().allMatch(n -> n.getKind() == NodeKind.ENTITY));
    }

    @Test
    void baseSettingsHasConfigDefinitionKind() {
        String code = """
                class Settings(BaseSettings):
                    database_url: str
                    redis_url: str
                    debug: bool = False
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("config.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.CONFIG_DEFINITION, result.nodes().get(0).getKind());
        assertEquals("BaseSettings", result.nodes().get(0).getProperties().get("base_class"));
    }

    @Test
    void noMatchOnEmptyContent() {
        DetectorContext ctx = DetectorTestUtils.contextFor("python", "");
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
    }

    @Test
    void filePathSetOnNode() {
        String code = """
                class Item(BaseModel):
                    name: str
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("models/item.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals("models/item.py", result.nodes().get(0).getFilePath());
    }

    @Test
    void fqnSetCorrectly() {
        String code = """
                class Response(BaseModel):
                    status: str
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertNotNull(result.nodes().get(0).getFqn());
        assertTrue(result.nodes().get(0).getFqn().contains("Response"));
    }

    // ---- Regex fallback path (content > 500KB) ----

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    @Test
    void regexFallback_detectsBaseModel() {
        String code = pad("""
                class UserCreate(BaseModel):
                    username: str
                    email: str
                    password: str
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENTITY
                && "UserCreate".equals(n.getLabel())),
                "regex fallback should detect BaseModel subclass");
        assertEquals("pydantic", result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow()
                .getProperties().get("framework"));
    }

    @Test
    void regexFallback_detectsBaseSettings() {
        String code = pad("""
                class AppSettings(BaseSettings):
                    database_url: str
                    debug: bool
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("config.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_DEFINITION
                && "AppSettings".equals(n.getLabel())),
                "regex fallback should detect BaseSettings as CONFIG_DEFINITION");
    }

    @Test
    void regexFallback_extractsFields() {
        String code = pad("""
                class Item(BaseModel):
                    name: str
                    price: float
                    quantity: int
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var node = result.nodes().stream().filter(n -> n.getKind() == NodeKind.ENTITY).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var fields = (List<?>) node.getProperties().get("fields");
        assertNotNull(fields);
        assertFalse(fields.isEmpty(), "fields should be extracted in regex fallback");
    }

    @Test
    void regexFallback_detectsInheritanceBetweenModels() {
        String code = pad("""
                class Base(BaseModel):
                    id: int

                class User(Base):
                    username: str
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("schemas.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        // Both classes should be detected (Base is BaseModel, User extends Base but regex only catches BaseModel/BaseSettings)
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect at least the BaseModel subclass");
    }
}
