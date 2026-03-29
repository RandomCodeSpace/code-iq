package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigDetectorsExtendedTest {

    // ==================== DockerComposeDetector ====================
    @Nested
    class DockerComposeExtended {
        private final DockerComposeDetector d = new DockerComposeDetector();

        @Test
        void detectsServicesWithNetworksAndVolumes() {
            Map<String, Object> parsed = Map.of("type", "yaml", "data", Map.of(
                    "services", Map.of(
                            "web", Map.of("image", "nginx:latest", "ports", List.of("80:80"),
                                    "depends_on", List.of("api"), "networks", List.of("frontend")),
                            "api", Map.of("build", "./api", "depends_on", List.of("db"),
                                    "environment", List.of("DB_HOST=db")),
                            "db", Map.of("image", "postgres:15", "volumes", List.of("pgdata:/var/lib/postgresql/data"))
                    ),
                    "networks", Map.of("frontend", Map.of()),
                    "volumes", Map.of("pgdata", Map.of())
            ));
            var ctx = new DetectorContext("docker-compose.yml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertTrue(r.nodes().size() >= 3);
            assertFalse(r.edges().isEmpty());
        }

        @Test
        void detectsServiceWithBuildContext() {
            Map<String, Object> parsed = Map.of("type", "yaml", "data", Map.of(
                    "services", Map.of(
                            "app", Map.of("build", Map.of("context", ".", "dockerfile", "Dockerfile.prod"),
                                    "ports", List.of("3000:3000")),
                            "worker", Map.of("build", "./worker", "command", "python worker.py")
                    )
            ));
            var ctx = new DetectorContext("docker-compose.yml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertTrue(r.nodes().size() >= 2);
        }
    }

    // ==================== CloudFormationDetector ====================
    @Nested
    class CloudFormationExtended {
        private final CloudFormationDetector d = new CloudFormationDetector();

        @Test
        void detectsMultipleResourceTypes() {
            Map<String, Object> resources = new HashMap<>();
            resources.put("WebServer", Map.of("Type", "AWS::EC2::Instance",
                    "Properties", Map.of("InstanceType", "t3.micro")));
            resources.put("AppBucket", Map.of("Type", "AWS::S3::Bucket",
                    "Properties", Map.of("BucketName", "my-app")));
            resources.put("Lambda", Map.of("Type", "AWS::Lambda::Function",
                    "Properties", Map.of("Runtime", "python3.11")));
            resources.put("UserTable", Map.of("Type", "AWS::DynamoDB::Table",
                    "Properties", Map.of("TableName", "users")));

            Map<String, Object> parsed = Map.of("type", "yaml", "data", Map.of(
                    "AWSTemplateFormatVersion", "2010-09-09",
                    "Resources", resources
            ));
            var ctx = new DetectorContext("template.yml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertTrue(r.nodes().size() >= 4);
        }

        @Test
        void detectsWithOutputsAndParameters() {
            Map<String, Object> parsed = Map.of("type", "yaml", "data", Map.of(
                    "AWSTemplateFormatVersion", "2010-09-09",
                    "Parameters", Map.of("Environment", Map.of("Type", "String", "Default", "dev")),
                    "Resources", Map.of("VPC", Map.of("Type", "AWS::EC2::VPC",
                            "Properties", Map.of("CidrBlock", "10.0.0.0/16"))),
                    "Outputs", Map.of("VpcId", Map.of("Value", "!Ref VPC"))
            ));
            var ctx = new DetectorContext("template.yml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== GitLabCiDetector ====================
    @Nested
    class GitLabCiExtended {
        private final GitLabCiDetector d = new GitLabCiDetector();

        @Test
        void detectsJobsWithStages() {
            Map<String, Object> data = new HashMap<>();
            data.put("stages", List.of("build", "test", "deploy"));
            data.put("build_job", Map.of("stage", "build", "script", List.of("mvn package")));
            data.put("test_job", Map.of("stage", "test", "script", List.of("mvn test"),
                    "needs", List.of("build_job")));
            data.put("deploy_prod", Map.of("stage", "deploy", "script", List.of("kubectl apply -f k8s/"),
                    "when", "manual"));

            Map<String, Object> parsed = Map.of("type", "yaml", "data", data);
            var ctx = new DetectorContext(".gitlab-ci.yml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertTrue(r.nodes().size() >= 3);
        }

        @Test
        void detectsIncludesAndVariables() {
            Map<String, Object> data = new HashMap<>();
            data.put("include", List.of(
                    Map.of("template", "Security/SAST.gitlab-ci.yml")
            ));
            data.put("variables", Map.of("DOCKER_HOST", "tcp://docker:2376"));
            data.put("build", Map.of("stage", "build", "image", "maven:3.9",
                    "script", List.of("mvn clean install")));

            Map<String, Object> parsed = Map.of("type", "yaml", "data", data);
            var ctx = new DetectorContext(".gitlab-ci.yml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== KubernetesDetector ====================
    @Nested
    class KubernetesExtended {
        private final KubernetesDetector d = new KubernetesDetector();

        @Test
        void detectsDeployment() {
            Map<String, Object> parsed = Map.of("type", "yaml", "data", Map.of(
                    "apiVersion", "apps/v1",
                    "kind", "Deployment",
                    "metadata", Map.of("name", "web-app", "namespace", "production"),
                    "spec", Map.of("replicas", 3,
                            "selector", Map.of("matchLabels", Map.of("app", "web-app")))
            ));
            var ctx = new DetectorContext("deployment.yml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsService() {
            Map<String, Object> parsed = Map.of("type", "yaml", "data", Map.of(
                    "apiVersion", "v1",
                    "kind", "Service",
                    "metadata", Map.of("name", "web-service"),
                    "spec", Map.of("type", "LoadBalancer",
                            "selector", Map.of("app", "web-app"),
                            "ports", List.of(Map.of("port", 80, "targetPort", 8080)))
            ));
            var ctx = new DetectorContext("service.yml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsConfigMap() {
            Map<String, Object> parsed = Map.of("type", "yaml", "data", Map.of(
                    "apiVersion", "v1",
                    "kind", "ConfigMap",
                    "metadata", Map.of("name", "app-config"),
                    "data", Map.of("DATABASE_URL", "postgres://localhost/mydb")
            ));
            var ctx = new DetectorContext("config.yml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsStatefulSet() {
            Map<String, Object> parsed = Map.of("type", "yaml", "data", Map.of(
                    "apiVersion", "apps/v1",
                    "kind", "StatefulSet",
                    "metadata", Map.of("name", "database"),
                    "spec", Map.of("replicas", 3,
                            "selector", Map.of("matchLabels", Map.of("app", "db")))
            ));
            var ctx = new DetectorContext("statefulset.yml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== HelmChartDetector ====================
    @Nested
    class HelmChartExtended {
        private final HelmChartDetector d = new HelmChartDetector();

        @Test
        void detectsChartWithDependencies() {
            Map<String, Object> parsed = Map.of("type", "yaml", "data", Map.of(
                    "apiVersion", "v2",
                    "name", "my-app",
                    "version", "1.0.0",
                    "type", "application",
                    "dependencies", List.of(
                            Map.of("name", "postgresql", "version", "12.1.0",
                                    "repository", "https://charts.bitnami.com/bitnami"),
                            Map.of("name", "redis", "version", "17.0.0",
                                    "repository", "https://charts.bitnami.com/bitnami")
                    )
            ));
            var ctx = new DetectorContext("Chart.yaml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsValuesYaml() {
            Map<String, Object> parsed = Map.of("type", "yaml", "data", Map.of(
                    "replicaCount", 3,
                    "image", Map.of("repository", "myapp", "tag", "latest"),
                    "service", Map.of("type", "ClusterIP", "port", 80)
            ));
            // values.yaml must be under charts/ or helm/ directory
            var ctx = new DetectorContext("helm/myapp/values.yaml", "yaml", "", parsed, null);
            var r = d.detect(ctx);
            assertFalse(r.nodes().isEmpty());
        }
    }
}
