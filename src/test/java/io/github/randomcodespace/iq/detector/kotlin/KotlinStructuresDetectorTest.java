package io.github.randomcodespace.iq.detector.kotlin;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KotlinStructuresDetectorTest {

    private final KotlinStructuresDetector d = new KotlinStructuresDetector();

    @Test
    void detectsClass() {
        String code = "class UserService(private val repo: UserRepository)\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS && "UserService".equals(n.getLabel())));
    }

    @Test
    void detectsDataClass() {
        String code = "data class User(val id: Long, val name: String)\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS && "User".equals(n.getLabel())));
    }

    @Test
    void detectsSealedClass() {
        String code = "sealed class Result<out T>\nclass Success<T>(val data: T) : Result<T>()\nclass Failure(val error: String) : Result<Nothing>()\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().filter(n -> n.getKind() == NodeKind.CLASS).count() >= 3);
    }

    @Test
    void detectsClassWithSupertype() {
        String code = "class AdminUser : BaseUser()\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
    }

    @Test
    void detectsInterface() {
        String code = "interface UserRepository {\n    fun findAll(): List<User>\n    fun save(user: User): User\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INTERFACE && "UserRepository".equals(n.getLabel())));
    }

    @Test
    void detectsObject() {
        String code = "object DatabaseConfig {\n    const val URL = \"jdbc:postgresql://localhost:5432/db\"\n}\n";
        DetectorResult r = d.detect(ctx(code));
        var obj = r.nodes().stream().filter(n -> "DatabaseConfig".equals(n.getLabel())).findFirst().orElseThrow();
        assertEquals("object", obj.getProperties().get("type"));
    }

    @Test
    void detectsFun() {
        String code = "fun processOrder(order: Order): Result<Unit> {\n    return Success(Unit)\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD && "processOrder".equals(n.getLabel())));
    }

    @Test
    void detectsSuspendFun() {
        String code = "suspend fun fetchUser(id: Long): User {\n    return repo.findById(id)\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD && "fetchUser".equals(n.getLabel())));
    }

    @Test
    void detectsOverrideFun() {
        String code = "override fun onCreate(savedInstanceState: Bundle?) {\n    super.onCreate(savedInstanceState)\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD && "onCreate".equals(n.getLabel())));
    }

    @Test
    void detectsImports() {
        String code = "import kotlinx.coroutines.launch\nimport io.ktor.server.application.*\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPORTS).count());
    }

    @Test
    void detectsClassAndInterface() {
        DetectorResult r = d.detect(ctx("class User\ninterface Repo\nfun findAll() {}"));
        assertTrue(r.nodes().size() >= 3);
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.kt", "kotlin", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("kotlin_structures", d.getName());
    }

    @Test
    void supportedLanguagesContainsKotlin() {
        assertTrue(d.getSupportedLanguages().contains("kotlin"));
    }

    @Test
    void deterministic() {
        String code = """
                import kotlinx.coroutines.launch
                import io.ktor.server.routing.*
                data class User(val id: Long, val name: String)
                sealed class ApiResult<out T>
                interface UserService {
                    suspend fun findById(id: Long): User
                }
                object Config {
                    const val PORT = 8080
                }
                fun main() {
                    launch { println("started") }
                }
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("kotlin", content);
    }
}
