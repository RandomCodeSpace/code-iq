"""Java language support for tree-sitter parsing."""

from __future__ import annotations

import tree_sitter
import tree_sitter_java


class JavaLanguageSupport:
    """Java language support with Spring-focused queries."""

    name: str = "java"
    extensions: tuple[str, ...] = (".java",)

    def get_language(self) -> tree_sitter.Language:
        return tree_sitter.Language(tree_sitter_java.language())

    def get_queries(self) -> dict[str, str]:
        return _JAVA_QUERIES.copy()


# ---------------------------------------------------------------
# Tree-sitter queries targeting Java + Spring annotation patterns
# ---------------------------------------------------------------

_JAVA_QUERIES: dict[str, str] = {
    "annotations": """
        (marker_annotation
            name: (identifier) @annotation.name)
        (annotation
            name: (identifier) @annotation.name
            arguments: (annotation_argument_list) @annotation.args)
    """,
    "class_declarations": """
        (class_declaration
            name: (identifier) @class.name
            superclass: (superclass)? @class.superclass
            interfaces: (super_interfaces)? @class.interfaces
            body: (class_body) @class.body)
    """,
    "method_declarations": """
        (method_declaration
            (modifiers)? @method.modifiers
            type: (_) @method.return_type
            name: (identifier) @method.name
            parameters: (formal_parameters) @method.params
            body: (block)? @method.body)
    """,
    "interface_declarations": """
        (interface_declaration
            name: (identifier) @interface.name
            body: (interface_body) @interface.body)
    """,
    "field_declarations": """
        (field_declaration
            (modifiers)? @field.modifiers
            type: (_) @field.type
            declarator: (variable_declarator
                name: (identifier) @field.name))
    """,
    "import_declarations": """
        (import_declaration
            (scoped_identifier) @import.path)
    """,
    "string_literals": """
        (string_literal) @string.value
    """,
}
