package io.github.randomcodespace.iq.intelligence;

/**
 * Heuristic classification of a file's role in the repository.
 * Determined by file extension and path conventions.
 */
public enum FileClassification {
    /** Production source code. */
    SOURCE,
    /** Configuration files (YAML, JSON, TOML, properties, etc.). */
    CONFIG,
    /** Documentation (Markdown, AsciiDoc, plain text, etc.). */
    DOC,
    /** Test code (paths containing test/, spec/, __tests__, etc.). */
    TEST,
    /** Generated code (paths containing generated/, build/, target/, etc.). */
    GENERATED
}
