"""Tests for generic multi-language detectors."""
from code_intelligence.detectors.base import DetectorContext
from code_intelligence.models.graph import NodeKind, EdgeKind


class TestGenericImportsDetector:
    def test_rust(self):
        source = b'''
use std::collections::HashMap;
pub struct Config {
    pub host: String,
}
pub trait Storage {
    fn get(&self, key: &str) -> Option<String>;
}
impl Storage for Config {
    fn get(&self, key: &str) -> Option<String> { None }
}
pub fn process_data(data: &[u8]) -> Vec<u8> { vec![] }
'''
        ctx = DetectorContext(file_path="lib.rs", language="rust", content=source, module_name="mylib")
        from code_intelligence.detectors.rust.rust_structures import RustStructuresDetector
        result = RustStructuresDetector().detect(ctx)
        classes = [n for n in result.nodes if n.kind == NodeKind.CLASS]
        interfaces = [n for n in result.nodes if n.kind == NodeKind.INTERFACE]
        methods = [n for n in result.nodes if n.kind == NodeKind.METHOD]
        assert len(classes) >= 1  # Config struct
        assert len(interfaces) >= 1  # Storage trait
        assert len(methods) >= 1

    def test_kotlin(self):
        source = b'''
package com.example.app
import com.example.core.Repository
data class User(val id: Int, val name: String)
interface UserService {
    fun getUser(id: Int): User
}
class UserServiceImpl(private val repo: Repository) : UserService {
    override fun getUser(id: Int): User = repo.findById(id)
}
'''
        ctx = DetectorContext(file_path="UserService.kt", language="kotlin", content=source, module_name="app")
        from code_intelligence.detectors.kotlin.kotlin_structures import KotlinStructuresDetector
        result = KotlinStructuresDetector().detect(ctx)
        classes = [n for n in result.nodes if n.kind == NodeKind.CLASS]
        interfaces = [n for n in result.nodes if n.kind == NodeKind.INTERFACE]
        assert len(classes) >= 2  # User + UserServiceImpl
        assert len(interfaces) >= 1

    def test_ruby(self):
        source = b'''
require 'json'
require_relative 'base_service'
module MyApp
  class UserController < ApplicationController
    def index
      render json: User.all
    end
    def show
      render json: User.find(params[:id])
    end
  end
end
'''
        ctx = DetectorContext(file_path="user_controller.rb", language="ruby", content=source, module_name="app")
        from code_intelligence.detectors.generic.imports_detector import GenericImportsDetector
        result = GenericImportsDetector().detect(ctx)
        classes = [n for n in result.nodes if n.kind == NodeKind.CLASS]
        methods = [n for n in result.nodes if n.kind == NodeKind.METHOD]
        assert len(classes) >= 1
        assert len(methods) >= 2


class TestDockerfileDetector:
    def test_from_instructions(self):
        source = b'''FROM python:3.12-slim AS builder
FROM node:20-alpine AS frontend
FROM builder AS final
COPY --from=frontend /app/dist /static
'''
        ctx = DetectorContext(file_path="Dockerfile", language="dockerfile", content=source, module_name="app")
        from code_intelligence.detectors.iac.dockerfile import DockerfileDetector
        result = DockerfileDetector().detect(ctx)
        infra_nodes = [n for n in result.nodes if n.kind == NodeKind.INFRA_RESOURCE]
        assert len(infra_nodes) >= 3  # python, node, builder
        images = {n.properties.get("image") for n in infra_nodes}
        assert "python:3.12-slim" in images
        assert "node:20-alpine" in images

    def test_expose_ports(self):
        source = b'''FROM nginx:latest
EXPOSE 80
EXPOSE 443
'''
        ctx = DetectorContext(file_path="Dockerfile", language="dockerfile", content=source, module_name="infra")
        from code_intelligence.detectors.iac.dockerfile import DockerfileDetector
        result = DockerfileDetector().detect(ctx)
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 2
        ports = {n.properties.get("port") for n in endpoints}
        assert "80" in ports
        assert "443" in ports

    def test_env_and_labels(self):
        source = b'''FROM python:3.12
ENV APP_PORT=8080
ENV DB_HOST=localhost
LABEL maintainer=team@example.com
LABEL version=1.0
'''
        ctx = DetectorContext(file_path="Dockerfile", language="dockerfile", content=source, module_name="app")
        from code_intelligence.detectors.iac.dockerfile import DockerfileDetector
        result = DockerfileDetector().detect(ctx)
        config_nodes = [n for n in result.nodes if n.kind == NodeKind.CONFIG_DEFINITION]
        assert len(config_nodes) >= 4  # 2 ENVs + 2 LABELs
        env_keys = {n.properties.get("env_key") for n in config_nodes if n.properties.get("env_key")}
        assert "APP_PORT" in env_keys
        assert "DB_HOST" in env_keys

    def test_depends_on_edges(self):
        source = b'''FROM python:3.12-slim
RUN pip install flask
EXPOSE 5000
'''
        ctx = DetectorContext(file_path="Dockerfile", language="dockerfile", content=source, module_name="app")
        from code_intelligence.detectors.iac.dockerfile import DockerfileDetector
        result = DockerfileDetector().detect(ctx)
        dep_edges = [e for e in result.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(dep_edges) >= 1
        assert dep_edges[0].target == "python:3.12-slim"
