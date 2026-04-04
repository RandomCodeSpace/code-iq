package io.github.randomcodespace.iq.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileHasherTest {

    @Test
    void hashProducesDeterministicResult(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello, World!", StandardCharsets.UTF_8);

        String hash1 = FileHasher.hash(file);
        String hash2 = FileHasher.hash(file);

        assertEquals(hash1, hash2, "Same file should produce same hash");
        assertEquals(64, hash1.length(), "SHA-256 hash should be 64 hex chars");
    }

    @Test
    void hashDiffersForDifferentContent(@TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("a.txt");
        Path file2 = tempDir.resolve("b.txt");
        Files.writeString(file1, "Content A", StandardCharsets.UTF_8);
        Files.writeString(file2, "Content B", StandardCharsets.UTF_8);

        assertNotEquals(FileHasher.hash(file1), FileHasher.hash(file2));
    }

    @Test
    void hashStringProducesDeterministicResult() {
        String hash1 = FileHasher.hashString("test content");
        String hash2 = FileHasher.hashString("test content");

        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());
    }

    @Test
    void hashStringDiffersForDifferentContent() {
        assertNotEquals(
                FileHasher.hashString("content A"),
                FileHasher.hashString("content B")
        );
    }

    @Test
    void hashIsLowercaseHex(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "data", StandardCharsets.UTF_8);

        String hash = FileHasher.hash(file);
        assertTrue(hash.matches("[0-9a-f]+"), "Hash should be lowercase hex");
    }
}
