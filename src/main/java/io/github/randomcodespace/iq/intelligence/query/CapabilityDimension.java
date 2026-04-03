package io.github.randomcodespace.iq.intelligence.query;

/**
 * Semantic dimensions of language intelligence used in the capability matrix.
 * Each dimension corresponds to a distinct analysis concern.
 */
public enum CapabilityDimension {
    /** Detection of symbol definitions (classes, functions, methods, variables). */
    SYMBOL_DEFINITIONS,
    /** Detection of symbol references and usages across files. */
    SYMBOL_REFERENCES,
    /** Resolution of import/require/use directives to target symbols. */
    IMPORT_RESOLUTION,
    /** Type information extraction (static types, inferred types, generics). */
    TYPE_INFO,
    /** Class hierarchy and interface/mixin relationship detection. */
    CLASS_HIERARCHY,
    /** Framework-specific semantics (annotations, decorators, conventions). */
    FRAMEWORK_SEMANTICS,
    /** ORM entity and relationship mapping detection. */
    ORM_ENTITY_MAPPING,
    /** Authentication and authorization pattern detection. */
    AUTH_SECURITY,
    /** Async, event-driven, and messaging pattern detection. */
    ASYNC_PATTERNS
}
