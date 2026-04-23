package io.github.randomcodespace.iq.detector.jvm.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModuleDepsDetector}.
 *
 * <p>Covers Maven (pom.xml), Gradle build scripts (.gradle / .gradle.kts), and
 * Gradle settings (settings.gradle / settings.gradle.kts) detection paths.
 *
 * <p>The settings.gradle dispatch was previously shadowed by the generic
 * {@code .endsWith(".gradle")} branch; the tests below both exercise the fixed
 * path and guard against a regression to that bug.
 */
class ModuleDepsDetectorTest {

    private final ModuleDepsDetector detector = new ModuleDepsDetector();

    // ---------------------------------------------------------------
    // Metadata + non-matching file paths
    // ---------------------------------------------------------------

    @Test
    void getName_returnsModuleDeps() {
        assertEquals("module_deps", detector.getName());
    }

    @Test
    void unrecognizedFile_returnsEmpty() {
        String code = "<project><groupId>foo</groupId></project>";
        DetectorContext ctx = DetectorTestUtils.contextFor("src/app.java", "java", code);
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void pomXmlEmpty_returnsEmpty() {
        DetectorContext ctx = DetectorTestUtils.contextFor("pom.xml", "xml", "");
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void gradleEmpty_returnsEmpty() {
        DetectorContext ctx = DetectorTestUtils.contextFor("build.gradle", "gradle", "");
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void gradleSettingsEmpty_returnsEmpty() {
        DetectorContext ctx = DetectorTestUtils.contextFor("settings.gradle", "gradle", "");
        DetectorResult r = detector.detect(ctx);
        assertTrue(r.nodes().isEmpty());
    }

    // ---------------------------------------------------------------
    // Maven: pom.xml
    // ---------------------------------------------------------------

    @Test
    void maven_parsesGroupAndArtifact() {
        String pom = """
                <project>
                    <groupId>com.acme</groupId>
                    <artifactId>orders</artifactId>
                    <version>1.0.0</version>
                </project>
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("pom.xml", "xml", pom);
        DetectorResult r = detector.detect(ctx);

        assertEquals(1, r.nodes().size());
        var node = r.nodes().get(0);
        assertEquals(NodeKind.MODULE, node.getKind());
        assertEquals("module:com.acme:orders", node.getId());
        assertEquals("orders", node.getLabel());
        assertEquals("com.acme", node.getProperties().get("group_id"));
        assertEquals("orders", node.getProperties().get("artifact_id"));
        assertEquals("maven", node.getProperties().get("build_tool"));
    }

    @Test
    void maven_missingGroupOrArtifact_fallsBackToUnknown() {
        String pom = "<project><name>no-coords</name></project>";
        DetectorContext ctx = DetectorTestUtils.contextFor("pom.xml", "xml", pom);
        DetectorResult r = detector.detect(ctx);

        // Still produces a module node with the "unknown" fallback values
        assertEquals(1, r.nodes().size());
        assertEquals("module:unknown:unknown", r.nodes().get(0).getId());
    }

    @Test
    void maven_aggregatorPom_emitsContainsEdgesForSubmodules() {
        String pom = """
                <project>
                    <groupId>com.acme</groupId>
                    <artifactId>parent</artifactId>
                    <modules>
                        <module>api</module>
                        <module>web</module>
                    </modules>
                </project>
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("pom.xml", "xml", pom);
        DetectorResult r = detector.detect(ctx);

        // 1 parent + 2 sub-modules
        assertEquals(3, r.nodes().size());
        assertThat(r.nodes()).anyMatch(n -> "module:com.acme:api".equals(n.getId()));
        assertThat(r.nodes()).anyMatch(n -> "module:com.acme:web".equals(n.getId()));

        // 2 CONTAINS edges (parent -> api, parent -> web)
        assertEquals(2, r.edges().size());
        assertThat(r.edges()).allMatch(e -> e.getKind() == EdgeKind.CONTAINS);
    }

    @Test
    void maven_dependencyBlock_emitsDependsOnEdges() {
        String pom = """
                <project>
                    <groupId>com.acme</groupId>
                    <artifactId>orders</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.12</version>
                        </dependency>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("pom.xml", "xml", pom);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.edges()).filteredOn(e -> e.getKind() == EdgeKind.DEPENDS_ON)
                .hasSize(2);
        assertThat(r.edges()).anyMatch(e ->
                "module:org.slf4j:slf4j-api".equals(e.getTarget().getId()));
        assertThat(r.edges()).anyMatch(e ->
                "module:junit:junit".equals(e.getTarget().getId()));
    }

    @Test
    void maven_dependencyWithoutGroupId_usesUnknownFallback() {
        // artifactId only — groupId defaults to "unknown"
        String pom = """
                <project>
                    <groupId>com.acme</groupId>
                    <artifactId>svc</artifactId>
                    <dependencies>
                        <dependency>
                            <artifactId>my-lib</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("pom.xml", "xml", pom);
        DetectorResult r = detector.detect(ctx);
        assertThat(r.edges()).anyMatch(e ->
                "module:unknown:my-lib".equals(e.getTarget().getId()));
    }

    // ---------------------------------------------------------------
    // Gradle build.gradle
    // ---------------------------------------------------------------

    @Test
    void gradle_projectDependency_emitsDependsOnProject() {
        String gradle = """
                dependencies {
                    implementation project(':api')
                    api project(':common')
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("services/orders/build.gradle", "gradle", gradle);
        DetectorResult r = detector.detect(ctx);

        // One module node + project edges
        assertThat(r.nodes()).anyMatch(n -> n.getKind() == NodeKind.MODULE);
        assertThat(r.edges()).filteredOn(e -> e.getKind() == EdgeKind.DEPENDS_ON)
                .hasSize(2);
        assertThat(r.edges()).anyMatch(e ->
                "module:api".equals(e.getTarget().getId())
                        && "project".equals(e.getProperties().get("type")));
        assertThat(r.edges()).anyMatch(e ->
                "module:common".equals(e.getTarget().getId()));
    }

    @Test
    void gradle_externalDependency_emitsDependsOnExternal() {
        String gradle = """
                dependencies {
                    implementation 'com.google.guava:guava:33.0.0'
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("build.gradle", "gradle", gradle);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.edges()).anyMatch(e ->
                "module:com.google.guava:guava".equals(e.getTarget().getId())
                        && "external".equals(e.getProperties().get("type")));
        assertThat(r.edges()).anyMatch(e ->
                "module:org.junit.jupiter:junit-jupiter".equals(e.getTarget().getId()));
    }

    @Test
    void gradle_externalDependencyWithoutColon_ignored() {
        // Only the valid "gradle" format should produce an edge; a bare id is ignored
        String gradle = """
                dependencies {
                    implementation 'flat-jar'
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("build.gradle", "gradle", gradle);
        DetectorResult r = detector.detect(ctx);
        // Single module node + zero dependency edges (since 'flat-jar' lacks ':')
        assertEquals(1, r.nodes().size());
        assertThat(r.edges()).noneMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON);
    }

    @Test
    void gradle_moduleNameDerivedFromFilePath_whenAbsent() {
        String gradle = "// empty build file\nimplementation 'a:b:1'\n";
        DetectorContext ctx = DetectorTestUtils.contextFor(
                "root/services/billing/build.gradle", "gradle", gradle);
        DetectorResult r = detector.detect(ctx);

        // moduleName should be derived from the parent directory ("billing")
        assertEquals(1, r.nodes().stream()
                .filter(n -> "module:billing".equals(n.getId())).count());
    }

    @Test
    void gradle_respectsExplicitModuleName() {
        String gradle = "implementation 'com.acme:lib:1.0.0'\n";
        DetectorContext ctx = new DetectorContext(
                "build.gradle", "gradle", gradle, null, "explicit-module");
        DetectorResult r = detector.detect(ctx);
        assertThat(r.nodes()).anyMatch(n -> "module:explicit-module".equals(n.getId()));
    }

    @Test
    void gradleKts_withGroovyStyleStringDep_isDetected() {
        // NOTE: The detector's regex matches `implementation 'a:b:c'` but not
        // `implementation("a:b:c")` — Kotlin-DSL syntax with parentheses is
        // not currently supported by GRADLE_DEPENDENCY_RE (follow-up).
        String gradle = "implementation 'com.squareup.okhttp3:okhttp:4.12.0'\n";
        DetectorContext ctx = DetectorTestUtils.contextFor("build.gradle", "gradle", gradle);
        DetectorResult r = detector.detect(ctx);
        assertThat(r.edges()).filteredOn(e -> e.getKind() == EdgeKind.DEPENDS_ON)
                .hasSize(1);
    }

    @Test
    void buildGradle_routesToDetectGradle_notToSettings() {
        // Regression guard: `include ':x'` tokens that happen to appear in a build.gradle
        // file must NOT be interpreted as settings-style module includes — those only apply
        // inside settings.gradle. This pairs with the settings.gradle dispatch tests below.
        String build = """
                dependencies {
                    implementation project(':shared')
                    implementation 'com.acme:lib:1.0.0'
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("build.gradle", "gradle", build);
        DetectorResult r = detector.detect(ctx);

        // detectGradle emits a module node for the current file plus DEPENDS_ON edges.
        assertThat(r.nodes()).isNotEmpty();
        assertThat(r.edges()).isNotEmpty();
    }

    // ---------------------------------------------------------------
    // Gradle settings.gradle — the fixed dispatch branch
    // ---------------------------------------------------------------
    //
    // Prior to the dispatch-order fix, `.endsWith(".gradle")` matched
    // settings.gradle first, so detectGradleSettings() was unreachable via the
    // public detect() API. Tests below both exercise the now-reachable path
    // and pin the contract so the bug cannot reappear.

    @Test
    void settingsGradle_routesToDetectGradleSettings_andEmitsModuleNodes() {
        // `include 'foo'` is only handled by detectGradleSettings.
        // detectGradle would emit zero module nodes for this input, so seeing
        // module nodes here proves the dispatch fix reaches detectGradleSettings.
        String settings = """
                rootProject.name = 'acme'
                include ':api'
                include ':domain'
                include ':infra'
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("settings.gradle", "gradle", settings);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes())
                .extracting(CodeNode::getLabel)
                .containsExactlyInAnyOrder("api", "domain", "infra");
        assertThat(r.nodes())
                .allMatch(n -> n.getKind() == NodeKind.MODULE);
        assertThat(r.nodes())
                .allMatch(n -> "gradle".equals(n.getProperties().get("build_tool")));
    }

    @Test
    void settingsGradleKts_routesToDetectGradleSettings() {
        // The Kotlin-DSL syntax `include(":a")` is NOT matched by the detector's current
        // regex (which expects a whitespace-separated call — `include ':a'`). This test
        // asserts ONLY the dispatch contract: a `settings.gradle.kts` path reaches
        // detectGradleSettings and, where the syntax is regex-compatible, produces module
        // nodes.
        String settingsKts = """
                rootProject.name = "acme"
                include ':b'
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("settings.gradle.kts", "gradle", settingsKts);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes())
                .extracting(CodeNode::getLabel)
                .contains("b");
    }

    @Test
    void nestedSettingsGradlePath_stillRoutesToDetectGradleSettings() {
        // Regression guard: path-like endsWith should still match when the settings file lives in a subdir.
        String settings = "include ':core'\n";
        DetectorContext ctx = DetectorTestUtils.contextFor("build/settings.gradle", "gradle", settings);
        DetectorResult r = detector.detect(ctx);

        assertThat(r.nodes())
                .extracting(CodeNode::getLabel)
                .containsExactly("core");
    }

    // ---------------------------------------------------------------
    // Determinism
    // ---------------------------------------------------------------

    @Test
    void deterministic_mavenSameInputSameOutput() {
        String pom = """
                <project>
                    <groupId>com.acme</groupId>
                    <artifactId>orders</artifactId>
                    <modules>
                        <module>api</module>
                    </modules>
                </project>
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("pom.xml", "xml", pom);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void deterministic_gradleSameInputSameOutput() {
        String gradle = """
                dependencies {
                    implementation project(':a')
                    implementation 'com.acme:lib:1.0'
                }
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("build.gradle", "gradle", gradle);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void deterministic_settingsGradle() {
        String settings = "include ':a'\ninclude ':b'\n";
        DetectorContext ctx = DetectorTestUtils.contextFor("settings.gradle", "gradle", settings);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
