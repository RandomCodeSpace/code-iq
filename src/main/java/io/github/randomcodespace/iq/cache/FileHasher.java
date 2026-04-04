package io.github.randomcodespace.iq.cache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes SHA-256 hash of file content for change detection.
 */
public final class FileHasher {

    private FileHasher() {
    }

    /**
     * Compute the SHA-256 hex digest of a file's content.
     *
     * @param file path to the file
     * @return lowercase hex SHA-256 hash string
     * @throws IOException if the file cannot be read
     */
    public static String hash(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (InputStream is = Files.newInputStream(file)) {
                int n;
                while ((n = is.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Compute the SHA-256 hex digest of a string's content (UTF-8 bytes).
     *
     * @param content the string to hash
     * @return lowercase hex SHA-256 hash string
     */
    public static String hashString(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
