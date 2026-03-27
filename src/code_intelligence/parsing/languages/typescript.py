"""TypeScript/JavaScript language support for tree-sitter parsing."""

from __future__ import annotations

import tree_sitter
import tree_sitter_typescript


class TypeScriptLanguageSupport:
    """Tree-sitter language support for TypeScript."""

    name: str = "typescript"
    extensions: tuple[str, ...] = (".ts", ".tsx")

    def get_language(self) -> tree_sitter.Language:
        return tree_sitter.Language(tree_sitter_typescript.language_typescript())

    def get_queries(self) -> dict[str, str]:
        return TYPESCRIPT_QUERIES


class JavaScriptLanguageSupport:
    """Tree-sitter language support for JavaScript."""

    name: str = "javascript"
    extensions: tuple[str, ...] = (".js", ".jsx")

    def get_language(self) -> tree_sitter.Language:
        import tree_sitter_javascript
        return tree_sitter.Language(tree_sitter_javascript.language())

    def get_queries(self) -> dict[str, str]:
        return JAVASCRIPT_QUERIES


TYPESCRIPT_QUERIES: dict[str, str] = {
    "class_declarations": """
        (class_declaration
          name: (type_identifier) @class.name
          body: (class_body) @class.body)
    """,
    "method_definitions": """
        (method_definition
          name: (property_identifier) @method.name
          parameters: (formal_parameters) @method.params)
    """,
    "function_declarations": """
        (function_declaration
          name: (identifier) @func.name
          parameters: (formal_parameters) @func.params)
    """,
    "decorators": """
        (decorator
          (call_expression
            function: (_) @decorator.name
            arguments: (arguments)? @decorator.args))
    """,
    "import_statements": """
        (import_statement
          source: (string) @import.source)
    """,
    "call_expressions": """
        (call_expression
          function: (_) @call.func
          arguments: (arguments) @call.args)
    """,
    "string_literals": """
        (string) @string
    """,
}

JAVASCRIPT_QUERIES: dict[str, str] = {
    "function_declarations": """
        (function_declaration
          name: (identifier) @func.name
          parameters: (formal_parameters) @func.params)
    """,
    "class_declarations": """
        (class_declaration
          name: (identifier) @class.name
          body: (class_body) @class.body)
    """,
    "call_expressions": """
        (call_expression
          function: (_) @call.func
          arguments: (arguments) @call.args)
    """,
    "import_statements": """
        (import_statement
          source: (string) @import.source)
    """,
    "string_literals": """
        (string) @string
    """,
}
