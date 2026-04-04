package io.github.randomcodespace.iq.detector.shell;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BashDetectorTest {

    private final BashDetector d = new BashDetector();

    @Test
    void detectsShebangModuleNode() {
        String code = "#!/bin/bash\necho \"hello\"\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
        var module = r.nodes().stream().filter(n -> n.getKind() == NodeKind.MODULE).findFirst().orElseThrow();
        assertEquals("bash", module.getProperties().get("shell"));
    }

    @Test
    void detectsShebangWithEnv() {
        String code = "#!/usr/bin/env bash\necho \"hi\"\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
    }

    @Test
    void detectsFunctionKeywordStyle() {
        String code = "#!/bin/bash\nfunction deploy() {\n  echo \"deploying\"\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD && "deploy".equals(n.getLabel())));
    }

    @Test
    void detectsFunctionParenStyle() {
        String code = "#!/bin/bash\nbuild() {\n  mvn package\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD && "build".equals(n.getLabel())));
    }

    @Test
    void detectsMultipleFunctions() {
        String code = """
                #!/bin/bash
                function build() {
                    mvn package
                }
                function test() {
                    mvn test
                }
                function deploy() {
                    kubectl apply -f manifest.yaml
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(3, r.nodes().stream().filter(n -> n.getKind() == NodeKind.METHOD).count());
    }

    @Test
    void detectsSourceImport() {
        String code = "#!/bin/bash\nsource ./utils.sh\n. ./config.sh\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.IMPORTS));
    }

    @Test
    void detectsExportVariable() {
        String code = "#!/bin/bash\nexport DATABASE_URL=postgres://localhost/mydb\nexport API_KEY=secret123\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.nodes().stream().filter(n -> n.getKind() == NodeKind.CONFIG_DEFINITION).count());
    }

    @Test
    void detectsDockerToolUsage() {
        String code = "#!/bin/bash\ndocker build -t myapp .\ndocker push myapp\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CALLS
                && "docker".equals(e.getProperties().get("tool"))));
    }

    @Test
    void detectsKubectlToolUsage() {
        String code = "#!/bin/bash\nkubectl apply -f deployment.yaml\nkubectl get pods\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CALLS
                && "kubectl".equals(e.getProperties().get("tool"))));
    }

    @Test
    void detectsTerraformToolUsage() {
        String code = "#!/bin/bash\nterraform init\nterraform plan\nterraform apply\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CALLS
                && "terraform".equals(e.getProperties().get("tool"))));
    }

    @Test
    void toolSeenOnlyOnce() {
        // Docker appears multiple times but should only produce one CALLS edge
        String code = "#!/bin/bash\ndocker build .\ndocker push .\ndocker run app\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(1, r.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.CALLS && "docker".equals(e.getProperties().get("tool")))
                .count());
    }

    @Test
    void toolInCommentNotDetected() {
        String code = "#!/bin/bash\n# docker build . -- this is a comment\necho \"done\"\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(0, r.edges().stream().filter(e -> e.getKind() == EdgeKind.CALLS).count());
    }

    @Test
    void detectsFunction() {
        DetectorResult r = d.detect(ctx("#!/bin/bash\nfunction deploy() {\n  docker build .\n}"));
        assertTrue(r.nodes().size() >= 2);
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.sh", "bash", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("bash", d.getName());
    }

    @Test
    void supportedLanguagesContainsBash() {
        assertTrue(d.getSupportedLanguages().contains("bash"));
    }

    @Test
    void deterministic() {
        String code = """
                #!/bin/bash
                source ./common.sh
                export APP_ENV=production
                export DATABASE_URL=postgres://localhost/prod
                function build() {
                    docker build -t myapp .
                }
                function deploy() {
                    kubectl apply -f k8s/
                    terraform apply
                }
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("bash", content);
    }
}
