package io.github.randomcodespace.iq.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePack;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackAssembler;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackRequest;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolsEvidenceTest {

    @Mock private io.github.randomcodespace.iq.query.QueryService queryService;
    @Mock private io.github.randomcodespace.iq.graph.GraphStore graphStore;
    @Mock private org.neo4j.graphdb.GraphDatabaseService graphDb;
    @Mock private io.github.randomcodespace.iq.query.StatsService statsService;
    @Mock private io.github.randomcodespace.iq.query.TopologyService topologyService;
    @Mock private EvidencePackAssembler assembler;

    private McpTools mcpTools;
    private ArtifactMetadata metadata;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        CodeIqConfig config = new CodeIqConfig();
        metadata = new ArtifactMetadata(
                "https://github.com/example/repo", "sha456", Instant.now(),
                "1", "2", Map.of("code-iq", "1.0"),
                Map.of(), "cafebabe");

        mcpTools = new McpTools(
                queryService, config, objectMapper,
                Optional.empty(), graphDb,
                statsService, topologyService, graphStore,
                Optional.of(assembler), Optional.of(metadata));
    }

    @Test
    void getEvidencePackReturnsPackJson() throws Exception {
        EvidencePack pack = new EvidencePack(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), metadata, CapabilityLevel.PARTIAL);
        when(assembler.assemble(any(EvidencePackRequest.class), any())).thenReturn(pack);

        String result = mcpTools.getEvidencePack("UserService", null, null, null);
        assertThat(result).contains("capabilityLevel");
        assertThat(result).contains("PARTIAL");
    }

    @Test
    void getEvidencePackReturnsErrorWhenAssemblerAbsent() {
        McpTools noAssembler = new McpTools(
                queryService, new CodeIqConfig(), objectMapper,
                Optional.empty(), graphDb,
                statsService, topologyService, graphStore,
                Optional.empty(), Optional.empty());

        String result = noAssembler.getEvidencePack("Foo", null, null, null);
        assertThat(result).contains("error");
    }

    @Test
    void getArtifactMetadataReturnsMetadataJson() {
        String result = mcpTools.getArtifactMetadata();
        assertThat(result).contains("sha456");
        assertThat(result).contains("cafebabe");
    }

    @Test
    void getArtifactMetadataReturnsErrorWhenAbsent() {
        McpTools noMeta = new McpTools(
                queryService, new CodeIqConfig(), objectMapper,
                Optional.empty(), graphDb,
                statsService, topologyService, graphStore,
                Optional.empty(), Optional.empty());

        String result = noMeta.getArtifactMetadata();
        assertThat(result).contains("error");
    }

    @Test
    void getEvidencePackIsDeterministic() throws Exception {
        EvidencePack pack = new EvidencePack(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), metadata, CapabilityLevel.EXACT);
        when(assembler.assemble(any(EvidencePackRequest.class), any())).thenReturn(pack);

        String r1 = mcpTools.getEvidencePack("Svc", null, null, false);
        String r2 = mcpTools.getEvidencePack("Svc", null, null, false);
        assertThat(r1).isEqualTo(r2);
    }
}
