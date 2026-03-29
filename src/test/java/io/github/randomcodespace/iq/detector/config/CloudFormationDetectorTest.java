package io.github.randomcodespace.iq.detector.config;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudFormationDetectorTest {

    private final CloudFormationDetector detector = new CloudFormationDetector();

    @Test
    void positiveMatch_resources() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of(
                        "AWSTemplateFormatVersion", "2010-09-09",
                        "Resources", Map.of(
                                "MyBucket", Map.of("Type", "AWS::S3::Bucket"),
                                "MyQueue", Map.of("Type", "AWS::SQS::Queue",
                                        "Properties", Map.of("QueueName", Map.of("Ref", "MyBucket")))
                        )
                )
        );
        DetectorContext ctx = new DetectorContext("template.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().stream().filter(n -> n.getKind() == NodeKind.INFRA_RESOURCE).count());
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEPENDS_ON));
    }

    @Test
    void positiveMatch_parameters() {
        Map<String, Object> parsedData = Map.of(
                "type", "json",
                "data", Map.of(
                        "AWSTemplateFormatVersion", "2010-09-09",
                        "Parameters", Map.of(
                                "Env", Map.of("Type", "String", "Default", "dev")
                        ),
                        "Resources", Map.of()
                )
        );
        DetectorContext ctx = new DetectorContext("stack.json", "json", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_DEFINITION));
    }

    @Test
    void negativeMatch_notCfn() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of("name", "not-cfn")
        );
        DetectorContext ctx = new DetectorContext("config.yaml", "yaml", "", parsedData, null);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void deterministic() {
        Map<String, Object> parsedData = Map.of(
                "type", "yaml",
                "data", Map.of(
                        "AWSTemplateFormatVersion", "2010-09-09",
                        "Resources", Map.of("Bucket", Map.of("Type", "AWS::S3::Bucket"))
                )
        );
        DetectorContext ctx = new DetectorContext("cfn.yaml", "yaml", "", parsedData, null);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
