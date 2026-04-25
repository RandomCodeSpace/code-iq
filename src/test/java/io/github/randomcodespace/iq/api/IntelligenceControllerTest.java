package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePack;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackAssembler;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackRequest;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadata;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.github.randomcodespace.iq.config.CodeIqConfigTestSupport;

class IntelligenceControllerTest {

    private MockMvc mockMvc;
    private EvidencePackAssembler assembler;
    private ArtifactMetadata metadata;
    private ArtifactMetadataProvider metadataProvider;

    @BeforeEach
    void setUp() {
        assembler = Mockito.mock(EvidencePackAssembler.class);
        metadata = new ArtifactMetadata(
                "https://github.com/example/repo", "abc123", Instant.now(),
                "1", "2", Map.of("codeiq", "1.0"),
                Map.of(), "deadbeef");
        metadataProvider = Mockito.mock(ArtifactMetadataProvider.class);
        when(metadataProvider.current()).thenReturn(metadata);

        CodeIqConfig config = new CodeIqConfig();
        CodeIqConfigTestSupport.override(config).rootPath(System.getProperty("java.io.tmpdir")).done();

        IntelligenceController controller = new IntelligenceController(assembler, metadataProvider, config);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void evidenceEndpointReturns200ForValidSymbol() throws Exception {
        EvidencePack pack = new EvidencePack(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), metadata, CapabilityLevel.EXACT);
        when(assembler.assemble(any(EvidencePackRequest.class), any())).thenReturn(pack);

        mockMvc.perform(get("/api/intelligence/evidence").param("symbol", "UserService"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilityLevel").value("EXACT"));
    }

    @Test
    void evidenceEndpointReturns400WhenNeitherSymbolNorFileProvided() throws Exception {
        mockMvc.perform(get("/api/intelligence/evidence"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void evidenceEndpointReturns400ForPathTraversal() throws Exception {
        mockMvc.perform(get("/api/intelligence/evidence")
                .param("file", "../../etc/passwd"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void evidenceEndpointRejectsSymlinkEscapingRoot(@TempDir Path tempDir) throws Exception {
        Path target = Files.createTempFile("codeiq-evidence-escape-", ".txt");
        try {
            Files.writeString(target, "TOP SECRET", StandardCharsets.UTF_8);
            Path link = tempDir.resolve("leak.txt");
            try {
                Files.createSymbolicLink(link, target.toAbsolutePath());
            } catch (UnsupportedOperationException | IOException unsupported) {
                // Filesystem does not support symlinks (e.g. Windows without privilege) — skip.
                return;
            }

            CodeIqConfig config = new CodeIqConfig();
            CodeIqConfigTestSupport.override(config).rootPath(tempDir.toAbsolutePath().toString()).done();
            IntelligenceController controller =
                    new IntelligenceController(assembler, metadataProvider, config);
            MockMvc symlinkMvc = MockMvcBuilders.standaloneSetup(controller).build();

            symlinkMvc.perform(get("/api/intelligence/evidence").param("file", "leak.txt"))
                    .andExpect(status().isBadRequest());
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void evidenceEndpointAllowsInRepoSymlink(@TempDir Path tempDir) throws Exception {
        Path real = tempDir.resolve("real.java");
        Files.writeString(real, "class C {}", StandardCharsets.UTF_8);
        Path link = tempDir.resolve("alias.java");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }

        EvidencePack pack = new EvidencePack(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), metadata, CapabilityLevel.EXACT);
        when(assembler.assemble(any(EvidencePackRequest.class), any())).thenReturn(pack);

        CodeIqConfig config = new CodeIqConfig();
        CodeIqConfigTestSupport.override(config).rootPath(tempDir.toAbsolutePath().toString()).done();
        IntelligenceController controller =
                new IntelligenceController(assembler, metadataProvider, config);
        MockMvc symlinkMvc = MockMvcBuilders.standaloneSetup(controller).build();

        symlinkMvc.perform(get("/api/intelligence/evidence").param("file", "alias.java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilityLevel").value("EXACT"));
    }

    @Test
    void manifestEndpointReturns200() throws Exception {
        mockMvc.perform(get("/api/intelligence/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitSha").value("abc123"));
    }

    @Test
    void capabilitiesEndpointReturnsMatrix() throws Exception {
        mockMvc.perform(get("/api/intelligence/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrix").isMap());
    }

    @Test
    void capabilitiesEndpointFiltersbyLanguage() throws Exception {
        mockMvc.perform(get("/api/intelligence/capabilities").param("language", "java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("java"))
                .andExpect(jsonPath("$.capabilities").isMap());
    }
}
