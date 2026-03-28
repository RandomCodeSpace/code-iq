"""Django authentication and authorization detector for Python source files."""

from __future__ import annotations

import re

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.utils import decode_text
from osscodeiq.models.graph import GraphNode, NodeKind, SourceLocation

# @login_required
_LOGIN_REQUIRED_RE = re.compile(r'@login_required\b')

# @permission_required("app.can_edit") or @permission_required("app.can_edit", ...)
_PERMISSION_REQUIRED_RE = re.compile(
    r'@permission_required\(\s*["\']([^"\']*)["\']'
)

# @user_passes_test(some_func) or @user_passes_test(lambda u: u.is_staff)
_USER_PASSES_TEST_RE = re.compile(
    r'@user_passes_test\(\s*([^,)\s]+)'
)

# class MyView(LoginRequiredMixin, ...):
# class MyView(PermissionRequiredMixin, ...):
# class MyView(UserPassesTestMixin, ...):
_MIXIN_RE = re.compile(
    r'class\s+(\w+)\s*\(([^)]*)\):'
)

_AUTH_MIXINS = {
    "LoginRequiredMixin": "login_required",
    "PermissionRequiredMixin": "permission_required",
    "UserPassesTestMixin": "user_passes_test",
}


def _line_number(text: str, pos: int) -> int:
    """Return 1-based line number for a character offset."""
    return text[:pos].count("\n") + 1


class DjangoAuthDetector:
    """Detects Django auth decorators and mixin patterns in Python source files."""

    name: str = "django_auth"
    supported_languages: tuple[str, ...] = ("python",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        # @login_required
        for m in _LOGIN_REQUIRED_RE.finditer(text):
            line = _line_number(text, m.start())
            result.nodes.append(GraphNode(
                id=f"auth:{ctx.file_path}:login_required:{line}",
                kind=NodeKind.GUARD,
                label="@login_required",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=["@login_required"],
                properties={
                    "auth_type": "django",
                    "permissions": [],
                    "auth_required": True,
                },
            ))

        # @permission_required("perm")
        for m in _PERMISSION_REQUIRED_RE.finditer(text):
            line = _line_number(text, m.start())
            permission = m.group(1)
            result.nodes.append(GraphNode(
                id=f"auth:{ctx.file_path}:permission_required:{line}",
                kind=NodeKind.GUARD,
                label=f"@permission_required({permission})",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=["@permission_required"],
                properties={
                    "auth_type": "django",
                    "permissions": [permission],
                    "auth_required": True,
                },
            ))

        # @user_passes_test(fn)
        for m in _USER_PASSES_TEST_RE.finditer(text):
            line = _line_number(text, m.start())
            test_func = m.group(1)
            result.nodes.append(GraphNode(
                id=f"auth:{ctx.file_path}:user_passes_test:{line}",
                kind=NodeKind.GUARD,
                label=f"@user_passes_test({test_func})",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=["@user_passes_test"],
                properties={
                    "auth_type": "django",
                    "permissions": [],
                    "test_function": test_func,
                    "auth_required": True,
                },
            ))

        # Class-based views with auth mixins
        for m in _MIXIN_RE.finditer(text):
            class_name = m.group(1)
            bases_str = m.group(2)
            bases = [b.strip() for b in bases_str.split(",")]

            for base in bases:
                if base in _AUTH_MIXINS:
                    line = _line_number(text, m.start())
                    result.nodes.append(GraphNode(
                        id=f"auth:{ctx.file_path}:{base}:{line}",
                        kind=NodeKind.GUARD,
                        label=f"{class_name}({base})",
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=line),
                        annotations=[f"mixin:{base}"],
                        properties={
                            "auth_type": "django",
                            "permissions": [],
                            "mixin": base,
                            "class_name": class_name,
                            "auth_required": True,
                        },
                    ))

        return result
