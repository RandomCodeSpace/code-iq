package io.github.randomcodespace.iq.detector;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Static utility methods for detectors.
 */
public final class DetectorUtils {

    private DetectorUtils() {
        // utility class
    }

    /**
     * Extension-to-language mapping, matching the Python _EXTENSION_MAP.
     */
    private static final Map<String, String> EXTENSION_MAP = Map.ofEntries(
            Map.entry(".java", "java"),
            Map.entry(".py", "python"),
            Map.entry(".pyi", "python"),
            Map.entry(".ts", "typescript"),
            Map.entry(".tsx", "typescript"),
            Map.entry(".mts", "typescript"),
            Map.entry(".cts", "typescript"),
            Map.entry(".js", "javascript"),
            Map.entry(".jsx", "javascript"),
            Map.entry(".mjs", "javascript"),
            Map.entry(".cjs", "javascript"),
            Map.entry(".xml", "xml"),
            Map.entry(".yaml", "yaml"),
            Map.entry(".yml", "yaml"),
            Map.entry(".json", "json"),
            Map.entry(".jsonc", "json"),
            Map.entry(".properties", "properties"),
            Map.entry(".gradle", "gradle"),
            Map.entry(".sql", "sql"),
            Map.entry(".graphql", "graphql"),
            Map.entry(".gql", "graphql"),
            Map.entry(".proto", "proto"),
            Map.entry(".md", "markdown"),
            Map.entry(".markdown", "markdown"),
            Map.entry(".bicep", "bicep"),
            Map.entry(".tf", "terraform"),
            Map.entry(".tfvars", "terraform"),
            Map.entry(".hcl", "terraform"),
            Map.entry(".cs", "csharp"),
            Map.entry(".go", "go"),
            Map.entry(".cpp", "cpp"),
            Map.entry(".cc", "cpp"),
            Map.entry(".cxx", "cpp"),
            Map.entry(".hpp", "cpp"),
            Map.entry(".c", "c"),
            Map.entry(".h", "c"),
            Map.entry(".sh", "bash"),
            Map.entry(".bash", "bash"),
            Map.entry(".zsh", "bash"),
            Map.entry(".ps1", "powershell"),
            Map.entry(".psm1", "powershell"),
            Map.entry(".psd1", "powershell"),
            Map.entry(".bat", "batch"),
            Map.entry(".cmd", "batch"),
            Map.entry(".rb", "ruby"),
            Map.entry(".rs", "rust"),
            Map.entry(".kt", "kotlin"),
            Map.entry(".kts", "kotlin"),
            Map.entry(".scala", "scala"),
            Map.entry(".swift", "swift"),
            Map.entry(".r", "r"),
            Map.entry(".R", "r"),
            Map.entry(".pl", "perl"),
            Map.entry(".pm", "perl"),
            Map.entry(".lua", "lua"),
            Map.entry(".dart", "dart"),
            Map.entry(".dockerfile", "dockerfile"),
            Map.entry(".toml", "toml"),
            Map.entry(".ini", "ini"),
            Map.entry(".cfg", "ini"),
            Map.entry(".conf", "ini"),
            Map.entry(".env", "dotenv"),
            Map.entry(".csv", "csv"),
            Map.entry(".vue", "vue"),
            Map.entry(".svelte", "svelte"),
            Map.entry(".html", "html"),
            Map.entry(".htm", "html"),
            Map.entry(".css", "css"),
            Map.entry(".scss", "scss"),
            Map.entry(".less", "less"),
            Map.entry(".groovy", "groovy"),
            Map.entry(".razor", "razor"),
            Map.entry(".cshtml", "cshtml"),
            Map.entry(".adoc", "asciidoc")
    );

    /**
     * Filename-to-language mapping for extensionless files.
     */
    private static final Map<String, String> FILENAME_MAP = Map.of(
            "Dockerfile", "dockerfile",
            "Makefile", "makefile",
            "GNUmakefile", "makefile",
            "Jenkinsfile", "groovy",
            "Vagrantfile", "ruby",
            "Gemfile", "ruby",
            "Rakefile", "ruby",
            "go.mod", "gomod",
            "go.sum", "gosum"
    );

    /**
     * Languages that use structured module name derivation (parent directory as module).
     */
    private static final Set<String> STRUCTURED_LANGUAGES = Set.of(
            "xml", "yaml", "json", "properties", "gradle", "sql",
            "bicep", "terraform", "csharp", "go", "cpp", "c",
            "bash", "powershell", "batch", "ruby", "rust", "kotlin",
            "scala", "swift", "r", "perl", "lua", "dart",
            "dockerfile", "toml", "ini", "dotenv", "csv",
            "vue", "svelte",
            "html", "css", "scss", "less", "razor", "cshtml", "asciidoc",
            "makefile", "gomod", "gosum", "groovy"
    );

    /**
     * Decode raw bytes to a String using UTF-8 with replacement for invalid sequences.
     */
    public static String decodeContent(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            CharBuffer result = decoder.decode(ByteBuffer.wrap(raw));
            return result.toString();
        } catch (Exception e) {
            // Should not happen with REPLACE action, but fallback just in case
            return new String(raw, StandardCharsets.UTF_8);
        }
    }

    /**
     * Derive the language from a file path based on extension or filename.
     *
     * @return the language string, or null if not recognized
     */
    public static String deriveLanguage(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }
        int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        String name = lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;

        // Check extensions (longer suffix matches win via iteration)
        for (var entry : EXTENSION_MAP.entrySet()) {
            if (name.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        // Fallback: match the full filename
        return FILENAME_MAP.get(name);
    }

    /**
     * Derive a module name from a file path and language.
     * Matches the Python _derive_module_name logic.
     *
     * @return the module name, or null if it cannot be derived
     */
    public static String deriveModuleName(String filePath, String language) {
        if (filePath == null || language == null) {
            return null;
        }
        // Normalize path separators
        String normalized = filePath.replace('\\', '/');

        if ("java".equals(language)) {
            for (String marker : new String[]{"src/main/java/", "src/test/java/"}) {
                int idx = normalized.indexOf(marker);
                if (idx >= 0) {
                    String remainder = normalized.substring(idx + marker.length());
                    int lastSep = remainder.lastIndexOf('/');
                    if (lastSep > 0) {
                        return remainder.substring(0, lastSep).replace('/', '.');
                    }
                    return null;
                }
            }
            return null;
        }

        if ("python".equals(language)) {
            int lastSep = normalized.lastIndexOf('/');
            if (lastSep <= 0) {
                return null;
            }
            String parent = normalized.substring(0, lastSep);
            return parent.replace('/', '.');
        }

        // For structured languages, use parent directory
        if (STRUCTURED_LANGUAGES.contains(language)) {
            int lastSep = normalized.lastIndexOf('/');
            if (lastSep <= 0) {
                return null;
            }
            String parent = normalized.substring(0, lastSep);
            return parent.replace('/', '.');
        }

        return null;
    }
}
