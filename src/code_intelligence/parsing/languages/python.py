"""Python language support for tree-sitter parsing."""

from __future__ import annotations

import tree_sitter
import tree_sitter_python


class PythonLanguageSupport:
    """Tree-sitter language support for Python."""

    name: str = "python"
    extensions: tuple[str, ...] = (".py",)

    def get_language(self) -> tree_sitter.Language:
        return tree_sitter.Language(tree_sitter_python.language())

    def get_queries(self) -> dict[str, str]:
        return PYTHON_QUERIES


PYTHON_QUERIES: dict[str, str] = {
    "function_definitions": """
        (function_definition
          name: (identifier) @func.name
          parameters: (parameters) @func.params
          body: (block) @func.body)
    """,
    "class_definitions": """
        (class_definition
          name: (identifier) @class.name
          body: (block) @class.body)
    """,
    "decorators": """
        (decorator
          (call
            function: (_) @decorator.name
            arguments: (argument_list)? @decorator.args)?)
    """,
    "import_statements": """
        (import_statement
          name: (dotted_name) @import.name)
    """,
    "import_from": """
        (import_from_statement
          module_name: (dotted_name)? @import.module
          name: (_)? @import.name)
    """,
    "string_literals": """
        (string) @string
    """,
    "assignments": """
        (assignment
          left: (_) @assign.target
          right: (_) @assign.value)
    """,
}
