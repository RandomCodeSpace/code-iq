"""Tests for TypeScriptStructuresDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.typescript.typescript_structures import TypeScriptStructuresDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(content, path="src/app.ts", language="typescript"):
    return DetectorContext(
        file_path=path,
        language=language,
        content=content.encode(),
    )


class TestTypeScriptStructuresDetector:
    def setup_method(self):
        self.detector = TypeScriptStructuresDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "typescript_structures"
        assert self.detector.supported_languages == ("typescript", "javascript")

    def test_detects_interfaces(self):
        src = '''\
export interface UserDTO {
    id: number;
    name: string;
}

interface InternalConfig {
    debug: boolean;
}
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        ifaces = [n for n in r.nodes if n.kind == NodeKind.INTERFACE]
        assert len(ifaces) == 2
        labels = {n.label for n in ifaces}
        assert labels == {"UserDTO", "InternalConfig"}
        # ID format
        user_dto = next(n for n in ifaces if n.label == "UserDTO")
        assert user_dto.id == "ts:src/app.ts:interface:UserDTO"

    def test_detects_type_aliases(self):
        src = '''\
export type UserID = string;
type Config = {
    port: number;
};
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        types = [n for n in r.nodes if n.kind == NodeKind.CLASS and n.properties.get("type_alias")]
        assert len(types) == 2
        labels = {n.label for n in types}
        assert labels == {"UserID", "Config"}
        # ID format
        user_id = next(n for n in types if n.label == "UserID")
        assert user_id.id == "ts:src/app.ts:type:UserID"

    def test_detects_classes(self):
        src = '''\
export class UserService {
    constructor() {}
}

export abstract class BaseService {
    abstract process(): void;
}

class InternalHelper {
    help() {}
}
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        classes = [n for n in r.nodes if n.kind == NodeKind.CLASS]
        assert len(classes) == 3
        labels = {n.label for n in classes}
        assert labels == {"UserService", "BaseService", "InternalHelper"}
        # ID format
        svc = next(n for n in classes if n.label == "UserService")
        assert svc.id == "ts:src/app.ts:class:UserService"

    def test_detects_functions(self):
        src = '''\
export function processUser(id: number): void {
}

export default function main(): void {
}

function internalHelper(): string {
    return "ok";
}

export async function fetchData(): Promise<void> {
}
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        methods = [n for n in r.nodes if n.kind == NodeKind.METHOD]
        assert len(methods) == 4
        labels = {n.label for n in methods}
        assert labels == {"processUser", "main", "internalHelper", "fetchData"}

        # default property
        main_node = next(n for n in methods if n.label == "main")
        assert main_node.properties.get("default") is True

        # async property
        fetch_node = next(n for n in methods if n.label == "fetchData")
        assert fetch_node.properties.get("async") is True

        # Regular function has neither
        process_node = next(n for n in methods if n.label == "processUser")
        assert "default" not in process_node.properties
        assert "async" not in process_node.properties

        # ID format
        assert process_node.id == "ts:src/app.ts:func:processUser"

    def test_detects_const_functions(self):
        src = '''\
export const handler = (req: Request) => {
    return new Response("ok");
};

export const asyncHandler = async (req: Request) => {
    return new Response("ok");
};
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        methods = [n for n in r.nodes if n.kind == NodeKind.METHOD]
        assert len(methods) == 2
        labels = {n.label for n in methods}
        assert labels == {"handler", "asyncHandler"}

        async_node = next(n for n in methods if n.label == "asyncHandler")
        assert async_node.properties.get("async") is True

    def test_detects_enums(self):
        src = '''\
export enum Status {
    Active = "active",
    Inactive = "inactive",
}

const enum Direction {
    Up,
    Down,
}

enum Color {
    Red,
    Green,
    Blue,
}
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        enums = [n for n in r.nodes if n.kind == NodeKind.ENUM]
        assert len(enums) == 3
        labels = {n.label for n in enums}
        assert labels == {"Status", "Direction", "Color"}
        # ID format
        status = next(n for n in enums if n.label == "Status")
        assert status.id == "ts:src/app.ts:enum:Status"

    def test_detects_imports(self):
        src = '''\
import { Router } from 'express';
import React from 'react';
import * as path from 'path';
import type { Config } from './config';
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        import_edges = [e for e in r.edges if e.kind == EdgeKind.IMPORTS]
        targets = {e.target for e in import_edges}
        assert "express" in targets
        assert "react" in targets
        assert "path" in targets
        assert "./config" in targets

    def test_detects_namespaces(self):
        src = '''\
export namespace API {
    export function getUser(): void {}
}

namespace Internal {
    function helper(): void {}
}
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        namespaces = [n for n in r.nodes if n.kind == NodeKind.MODULE]
        assert len(namespaces) == 2
        labels = {n.label for n in namespaces}
        assert labels == {"API", "Internal"}
        # ID format
        api = next(n for n in namespaces if n.label == "API")
        assert api.id == "ts:src/app.ts:namespace:API"

    def test_empty_returns_empty(self):
        ctx = _ctx("")
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_comments_only_returns_empty(self):
        ctx = _ctx("// just a comment\n/* block comment */\n")
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_determinism(self):
        src = '''\
export interface Foo {
    bar: string;
}

export function baz(): void {}

export enum Status {
    A, B
}
'''
        ctx = _ctx(src)
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]
        assert len(r1.edges) == len(r2.edges)

    def test_returns_detector_result(self):
        ctx = _ctx("")
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)

    def test_javascript_language(self):
        """Detector also supports JavaScript files."""
        src = '''\
export function handler(req) {
    return "ok";
}
'''
        ctx = _ctx(src, path="src/handler.js", language="javascript")
        r = self.detector.detect(ctx)
        methods = [n for n in r.nodes if n.kind == NodeKind.METHOD]
        assert len(methods) == 1
        assert methods[0].label == "handler"
